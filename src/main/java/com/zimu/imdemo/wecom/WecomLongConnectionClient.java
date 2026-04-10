package com.zimu.imdemo.wecom;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.zimu.imdemo.config.WecomProperties;
import com.zimu.imdemo.handler.MessageHandler;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.net.URI;
import java.security.KeyStore;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 企业微信 AI 机器人长连接客户端。
 * 完整实现官方 WebSocket 协议：鉴权订阅、心跳保活、消息收发、ACK 确认、断线重连。
 * 基于 Python SDK (wecom_aibot_python_sdk-1.0.2) 转换实现。
 *
 * @author Kyle
 * Created on 2026/04/10
 */
@Component
public class WecomLongConnectionClient {

    private static final Logger log = LoggerFactory.getLogger(WecomLongConnectionClient.class);

    /** 默认企业微信长连接地址 */
    private static final String DEFAULT_WS_URL = "wss://openws.work.weixin.qq.com";

    /** 心跳间隔（毫秒） */
    private static final long HEARTBEAT_INTERVAL_MS = 30000;

    /** 最大连续丢失心跳次数，超过则断开重连 */
    private static final int MAX_MISSED_PONG = 2;

    /** 回复 ACK 等待超时（毫秒） */
    private static final long REPLY_ACK_TIMEOUT_MS = 5000;

    /** 最大重连尝试次数（-1 表示无限重连） */
    private static final int MAX_RECONNECT_ATTEMPTS = -1;

    /** 重连基础间隔（毫秒） */
    private static final long BASE_RECONNECT_INTERVAL_MS = 1000;

    /** 最大重连间隔（毫秒） */
    private static final long MAX_RECONNECT_INTERVAL_MS = 30000;

    @Autowired
    private WecomProperties wecomProperties;

    @Autowired
    private MessageHandler messageHandler;

    /** 当前 WebSocket 连接实例 */
    private WebSocketClient webSocketClient;

    /** 是否已通过鉴权 */
    private volatile boolean authenticated = false;

    /** 连续丢失心跳响应次数 */
    private final AtomicInteger missedPongCount = new AtomicInteger(0);

    /** 当前重连次数 */
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);

    /** 心跳调度器 */
    private ScheduledExecutorService heartbeatScheduler;

    /** 重连调度器 */
    private final ScheduledExecutorService reconnectScheduler =
            Executors.newSingleThreadScheduledExecutor();

    /** 回复 ACK 等待 Future 表，key 为 req_id */
    private final Map<String, CompletableFuture<JSONObject>> pendingAcks = new ConcurrentHashMap<>();

    /** 回复串行队列，key 为 req_id，保证同一 req_id 的回复按顺序发送 */
    private final Map<String, LinkedBlockingQueue<JSONObject>> replyQueues = new ConcurrentHashMap<>();

    /** 回复队列处理线程池 */
    private final ExecutorService replyExecutor = Executors.newCachedThreadPool();

    /** 主动停止标志，避免 disconnect 后触发重连 */
    private volatile boolean manuallyStopped = false;

    // ==================== 连接管理 ====================

    /**
     * 应用完全启动后自动建立企业微信长连接。
     *
     * @author Kyle
     * Created on 2026/04/10
     */
    @EventListener(ApplicationReadyEvent.class)
    public void connect() {
        String botId = wecomProperties.getBot().getId();
        String secret = wecomProperties.getBot().getSecret();

        if (botId == null || secret == null || botId.isEmpty() || secret.isEmpty()) {
            log.warn("【企业微信】Bot ID 或 Secret 未配置，请在 application.yml 中填写后重启");
            return;
        }

        manuallyStopped = false;
        reconnectAttempts.set(0);
        log.info("【企业微信】正在建立长连接，Bot ID: {}", botId);
        doConnect();
    }

    /**
     * 建立 WebSocket 连接，设置 SSL 上下文和事件处理。
     *
     * @author Kyle
     * Created on 2026/04/10
     */
    private void doConnect() {
        try {
            URI uri = new URI(DEFAULT_WS_URL);
            webSocketClient = new WebSocketClient(uri) {

                @Override
                public void onOpen(ServerHandshake handshake) {
                    log.info("【企业微信】WebSocket 连接已建立，开始发送鉴权帧...");
                    reconnectAttempts.set(0);
                    sendSubscribe();
                }

                @Override
                public void onMessage(String rawMessage) {
                    handleFrame(rawMessage);
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    log.warn("【企业微信】连接关闭，code={}, reason={}, remote={}", code, reason, remote);
                    authenticated = false;
                    stopHeartbeat();
                    clearPendingAcks("连接已关闭");
                    if (!manuallyStopped) {
                        scheduleReconnect();
                    }
                }

                @Override
                public void onError(Exception ex) {
                    log.error("【企业微信】WebSocket 错误", ex);
                }
            };

            // 配置 SSL
            SSLContext sslContext = SSLContext.getInstance("TLS");
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);
            sslContext.init(null, tmf.getTrustManagers(), null);
            webSocketClient.setSocketFactory(sslContext.getSocketFactory());

            webSocketClient.connect();

        } catch (Exception e) {
            log.error("【企业微信】建立连接失败", e);
            if (!manuallyStopped) {
                scheduleReconnect();
            }
        }
    }

    /**
     * 按指数退避策略安排重连。
     * 重连延迟从 1s 开始，每次翻倍，最大 30s。
     *
     * @author Kyle
     * Created on 2026/04/10
     */
    private void scheduleReconnect() {
        int attempt = reconnectAttempts.incrementAndGet();
        if (MAX_RECONNECT_ATTEMPTS > 0 && attempt > MAX_RECONNECT_ATTEMPTS) {
            log.error("【企业微信】已达最大重连次数 {}，停止重连", MAX_RECONNECT_ATTEMPTS);
            return;
        }

        long delay = Math.min(BASE_RECONNECT_INTERVAL_MS * (1L << (attempt - 1)), MAX_RECONNECT_INTERVAL_MS);
        log.info("【企业微信】第 {} 次重连，{}ms 后执行...", attempt, delay);

        reconnectScheduler.schedule(() -> {
            log.info("【企业微信】正在执行重连...");
            doConnect();
        }, delay, TimeUnit.MILLISECONDS);
    }

    // ==================== 鉴权 ====================

    /**
     * 发送鉴权订阅帧（aibot_subscribe），携带 Bot ID 和 Secret。
     *
     * @author Kyle
     * Created on 2026/04/10
     */
    private void sendSubscribe() {
        JSONObject frame = new JSONObject();
        frame.put("cmd", WsCmd.SUBSCRIBE);

        JSONObject headers = new JSONObject();
        headers.put("req_id", WsUtils.generateReqId(WsCmd.SUBSCRIBE));
        frame.put("headers", headers);

        JSONObject body = new JSONObject();
        body.put("bot_id", wecomProperties.getBot().getId());
        body.put("secret", wecomProperties.getBot().getSecret());
        frame.put("body", body);

        sendFrame(frame);
        log.debug("【企业微信】鉴权帧已发送");
    }

    // ==================== 心跳 ====================

    /**
     * 启动心跳定时任务，每 30 秒发送一次 ping 帧。
     *
     * @author Kyle
     * Created on 2026/04/10
     */
    private void startHeartbeat() {
        stopHeartbeat();
        missedPongCount.set(0);
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();
        heartbeatScheduler.scheduleAtFixedRate(this::sendHeartbeat,
                HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info("【企业微信】心跳任务已启动，间隔 {}ms", HEARTBEAT_INTERVAL_MS);
    }

    /**
     * 停止心跳定时任务。
     *
     * @author Kyle
     * Created on 2026/04/10
     */
    private void stopHeartbeat() {
        if (heartbeatScheduler != null && !heartbeatScheduler.isShutdown()) {
            heartbeatScheduler.shutdownNow();
            heartbeatScheduler = null;
        }
    }

    /**
     * 发送心跳 ping 帧。连续丢失超过阈值时主动关闭连接触发重连。
     *
     * @author Kyle
     * Created on 2026/04/10
     */
    private void sendHeartbeat() {
        if (missedPongCount.get() >= MAX_MISSED_PONG) {
            log.warn("【企业微信】连续 {} 次心跳无响应，主动断开连接", MAX_MISSED_PONG);
            if (webSocketClient != null && webSocketClient.isOpen()) {
                webSocketClient.close();
            }
            return;
        }

        missedPongCount.incrementAndGet();

        JSONObject frame = new JSONObject();
        frame.put("cmd", WsCmd.HEARTBEAT);

        JSONObject headers = new JSONObject();
        headers.put("req_id", WsUtils.generateReqId(WsCmd.HEARTBEAT));
        frame.put("headers", headers);

        sendFrame(frame);
        log.debug("【企业微信】心跳 ping 已发送，当前丢失次数: {}", missedPongCount.get());
    }

    // ==================== 帧处理 ====================

    /**
     * 处理收到的 WebSocket 帧，按命令类型分发。
     *
     * @param rawMessage 原始 JSON 字符串
     * @author Kyle
     * Created on 2026/04/10
     */
    private void handleFrame(String rawMessage) {
        try {
            JSONObject frame = JSON.parseObject(rawMessage);
            if (frame == null) {
                log.warn("【企业微信】收到空帧，忽略");
                return;
            }

            String cmd = frame.getString("cmd");
            JSONObject headers = frame.getJSONObject("headers");
            String reqId = headers != null ? headers.getString("req_id") : null;

            log.debug("【企业微信】收到帧，cmd={}, req_id={}", cmd, reqId);

            // 鉴权响应
            if (reqId != null && reqId.startsWith(WsCmd.SUBSCRIBE)) {
                handleSubscribeResponse(frame);
                return;
            }

            // 心跳响应
            if (reqId != null && reqId.startsWith(WsCmd.HEARTBEAT)) {
                handleHeartbeatResponse(frame);
                return;
            }

            // 回复 ACK
            if (reqId != null && pendingAcks.containsKey(reqId)) {
                handleReplyAck(reqId, frame);
                return;
            }

            // 消息回调
            if (WsCmd.CALLBACK.equals(cmd)) {
                messageHandler.handle(frame, this);
                return;
            }

            // 事件回调
            if (WsCmd.EVENT_CALLBACK.equals(cmd)) {
                messageHandler.handleEvent(frame, this);
                return;
            }

            log.debug("【企业微信】未识别的帧类型，cmd={}", cmd);

        } catch (Exception e) {
            log.error("【企业微信】帧解析失败，原始内容: {}", rawMessage, e);
        }
    }

    /**
     * 处理鉴权订阅响应。成功则启动心跳，失败则打印错误。
     *
     * @param frame 鉴权响应帧
     * @author Kyle
     * Created on 2026/04/10
     */
    private void handleSubscribeResponse(JSONObject frame) {
        int errcode = frame.getIntValue("errcode");
        if (errcode == 0) {
            authenticated = true;
            log.info("【企业微信】鉴权成功！开始接收消息");
            startHeartbeat();
        } else {
            String errmsg = frame.getString("errmsg");
            log.error("【企业微信】鉴权失败，errcode={}, errmsg={}", errcode, errmsg);
        }
    }

    /**
     * 处理心跳 pong 响应，重置丢失计数。
     *
     * @param frame 心跳响应帧
     * @author Kyle
     * Created on 2026/04/10
     */
    private void handleHeartbeatResponse(JSONObject frame) {
        int errcode = frame.getIntValue("errcode");
        if (errcode == 0) {
            missedPongCount.set(0);
            log.debug("【企业微信】心跳 pong 正常");
        } else {
            log.warn("【企业微信】心跳响应异常，errcode={}", errcode);
        }
    }

    /**
     * 处理回复 ACK，通知等待中的 Future。
     *
     * @param reqId 请求 ID
     * @param frame ACK 帧
     * @author Kyle
     * Created on 2026/04/10
     */
    private void handleReplyAck(String reqId, JSONObject frame) {
        CompletableFuture<JSONObject> future = pendingAcks.remove(reqId);
        if (future != null) {
            int errcode = frame.getIntValue("errcode");
            if (errcode == 0) {
                future.complete(frame);
            } else {
                future.completeExceptionally(new RuntimeException(
                        "回复 ACK 失败，errcode=" + errcode + ", errmsg=" + frame.getString("errmsg")));
            }
        }
    }

    /**
     * 清理所有等待中的 ACK Future（连接断开时调用）。
     *
     * @param reason 断开原因
     * @author Kyle
     * Created on 2026/04/10
     */
    private void clearPendingAcks(String reason) {
        pendingAcks.forEach((reqId, future) ->
                future.completeExceptionally(new RuntimeException(reason)));
        pendingAcks.clear();
    }

    // ==================== 发送 ====================

    /**
     * 发送 WebSocket 帧（JSON 序列化后发送）。
     *
     * @param frame 要发送的 JSON 帧
     * @author Kyle
     * Created on 2026/04/10
     */
    public void sendFrame(JSONObject frame) {
        if (webSocketClient != null && webSocketClient.isOpen()) {
            String json = frame.toJSONString();
            webSocketClient.send(json);
            log.debug("【企业微信】发送帧: {}", json);
        } else {
            log.warn("【企业微信】连接未就绪，无法发送帧");
        }
    }

    /**
     * 发送回复帧并等待 ACK 确认。使用串行队列保证同一 req_id 的回复顺序。
     *
     * @param reqId 原始消息的请求 ID
     * @param frame 回复帧
     * @author Kyle
     * Created on 2026/04/10
     */
    public void sendReply(String reqId, JSONObject frame) {
        replyQueues.computeIfAbsent(reqId, k -> {
            LinkedBlockingQueue<JSONObject> queue = new LinkedBlockingQueue<>();
            // 启动该 req_id 的串行处理线程
            replyExecutor.submit(() -> processReplyQueue(reqId, queue));
            return queue;
        });
        replyQueues.get(reqId).offer(frame);
    }

    /**
     * 串行处理回复队列：逐帧发送并等待 ACK。
     *
     * @param reqId 请求 ID
     * @param queue 回复帧队列
     * @author Kyle
     * Created on 2026/04/10
     */
    private void processReplyQueue(String reqId, LinkedBlockingQueue<JSONObject> queue) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                JSONObject frame = queue.poll(60, TimeUnit.SECONDS);
                if (frame == null) {
                    // 超时无新帧，清理队列
                    replyQueues.remove(reqId);
                    return;
                }

                CompletableFuture<JSONObject> ackFuture = new CompletableFuture<>();
                pendingAcks.put(reqId, ackFuture);
                sendFrame(frame);

                try {
                    ackFuture.get(REPLY_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    log.debug("【企业微信】回复 ACK 确认成功，req_id={}", reqId);
                } catch (TimeoutException e) {
                    pendingAcks.remove(reqId);
                    log.warn("【企业微信】回复 ACK 等待超时，req_id={}", reqId);
                } catch (ExecutionException e) {
                    log.error("【企业微信】回复 ACK 异常，req_id={}", reqId, e.getCause());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            replyQueues.remove(reqId);
        }
    }

    // ==================== 便捷回复方法 ====================

    /**
     * 回复文本消息（使用 stream 类型，finish=true 表示一次性完整回复）。
     * 企业微信 aibot_respond_msg 不支持 text 类型，统一使用 stream 回复。
     *
     * @param receivedFrame 收到的原始消息帧
     * @param textContent   回复的文本内容（支持 Markdown）
     * @author Kyle
     * Created on 2026/04/10
     */
    public void replyText(JSONObject receivedFrame, String textContent) {
        String streamId = WsUtils.generateReqId("stream");
        replyStream(receivedFrame, streamId, textContent, true);
    }

    /**
     * 发送流式回复帧。
     *
     * @param receivedFrame 收到的原始消息帧
     * @param streamId      流式回复 ID（同一次流式回复使用相同 ID）
     * @param content       当前片段的 Markdown 文本内容
     * @param finish        是否为最后一个片段
     * @author Kyle
     * Created on 2026/04/10
     */
    public void replyStream(JSONObject receivedFrame, String streamId,
                            String content, boolean finish) {
        String reqId = receivedFrame.getJSONObject("headers").getString("req_id");

        JSONObject frame = new JSONObject();
        frame.put("cmd", WsCmd.RESPONSE);

        JSONObject headers = new JSONObject();
        headers.put("req_id", reqId);
        frame.put("headers", headers);

        JSONObject body = new JSONObject();
        body.put("msgtype", "stream");
        JSONObject stream = new JSONObject();
        stream.put("id", streamId);
        stream.put("content", content);
        stream.put("finish", finish);
        body.put("stream", stream);
        frame.put("body", body);

        sendReply(reqId, frame);
    }

    /**
     * 主动向指定会话发送消息。
     *
     * @param chatId  目标会话 ID（用户 ID 或群聊 ID）
     * @param msgType 消息类型（text / markdown）
     * @param content 消息内容
     * @author Kyle
     * Created on 2026/04/10
     */
    public void sendMessage(String chatId, String msgType, String content) {
        String reqId = WsUtils.generateReqId(WsCmd.SEND_MSG);

        JSONObject frame = new JSONObject();
        frame.put("cmd", WsCmd.SEND_MSG);

        JSONObject headers = new JSONObject();
        headers.put("req_id", reqId);
        frame.put("headers", headers);

        JSONObject body = new JSONObject();
        body.put("chatid", chatId);
        body.put("msgtype", msgType);

        JSONObject typeContent = new JSONObject();
        typeContent.put("content", content);
        body.put(msgType, typeContent);
        frame.put("body", body);

        sendReply(reqId, frame);
    }

    // ==================== 生命周期 ====================

    /**
     * 判断当前是否已鉴权成功。
     *
     * @return true 表示已鉴权
     * @author Kyle
     * Created on 2026/04/10
     */
    public boolean isAuthenticated() {
        return authenticated;
    }

    /**
     * 应用关闭时优雅释放所有资源。
     *
     * @author Kyle
     * Created on 2026/04/10
     */
    @PreDestroy
    public void disconnect() {
        manuallyStopped = true;
        stopHeartbeat();
        reconnectScheduler.shutdownNow();
        replyExecutor.shutdownNow();
        clearPendingAcks("应用关闭");
        if (webSocketClient != null && webSocketClient.isOpen()) {
            webSocketClient.close();
            log.info("【企业微信】长连接已关闭");
        }
    }
}
