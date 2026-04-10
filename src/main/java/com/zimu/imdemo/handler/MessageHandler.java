package com.zimu.imdemo.handler;

import com.alibaba.fastjson.JSONObject;
import com.zimu.imdemo.ai.DeepSeekService;
import com.zimu.imdemo.model.WecomReceiveMessage;
import com.zimu.imdemo.wecom.WecomLongConnectionClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 核心消息处理器。
 * 接收企业微信推送的消息和事件，调用 DeepSeek AI 生成回复，通过 WebSocket 帧发回。
 * 使用异步线程池处理，避免阻塞 WebSocket 消息接收线程。
 *
 * @author Kyle
 * Created on 2026/04/10
 */
@Component
public class MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(MessageHandler.class);

    @Autowired
    private DeepSeekService deepSeekService;

    /** 异步处理线程池，防止 AI 调用耗时阻塞 WebSocket 接收 */
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    /**
     * 处理企业微信消息回调（aibot_msg_callback）。
     *
     * @param frame  收到的原始 JSON 帧
     * @param client WebSocket 客户端（用于回复）
     * @author Kyle
     * Created on 2026/04/10
     */
    public void handle(JSONObject frame, WecomLongConnectionClient client) {
        WecomReceiveMessage message = new WecomReceiveMessage(frame);
        log.info("【消息处理】收到消息，类型: {}, 用户: {}({}), 会话: {}",
                message.getMsgType(), message.getFromUserName(),
                message.getFromUserId(), message.getChatId());

        executor.submit(() -> {
            try {
                processMessage(message, client);
            } catch (Exception e) {
                log.error("【消息处理】处理消息异常，用户: {}", message.getFromUserId(), e);
            }
        });
    }

    /**
     * 处理企业微信事件回调（aibot_event_callback）。
     *
     * @param frame  收到的原始事件 JSON 帧
     * @param client WebSocket 客户端（用于回复）
     * @author Kyle
     * Created on 2026/04/10
     */
    public void handleEvent(JSONObject frame, WecomLongConnectionClient client) {
        JSONObject body = frame.getJSONObject("body");
        if (body == null) {
            return;
        }

        JSONObject event = body.getJSONObject("event");
        String eventType = event != null ? event.getString("eventtype") : "unknown";
        log.info("【事件处理】收到事件，类型: {}", eventType);

        // 进入聊天事件 — 可回复欢迎语（5秒内）
        if ("enter_chat".equals(eventType)) {
            executor.submit(() -> {
                try {
                    client.replyText(frame, "你好！我是 AI 助手，有什么可以帮你的吗？");
                } catch (Exception e) {
                    log.error("【事件处理】回复欢迎语失败", e);
                }
            });
        }
    }

    /**
     * 根据消息类型执行具体处理逻辑。
     *
     * @param message 消息封装对象
     * @param client  WebSocket 客户端
     * @author Kyle
     * Created on 2026/04/10
     */
    private void processMessage(WecomReceiveMessage message, WecomLongConnectionClient client) {
        String msgType = message.getMsgType();

        if ("text".equals(msgType)) {
            handleTextMessage(message, client);
        } else if ("voice".equals(msgType)) {
            // 语音消息已被企业微信自动转写为文字
            handleVoiceMessage(message, client);
        } else {
            client.replyText(message.getRawFrame(), "抱歉，目前只支持文字和语音消息，请发送文字进行对话~");
        }
    }

    /**
     * 处理文本消息：发送给 DeepSeek AI 获取回复。
     *
     * @param message 文本消息
     * @param client  WebSocket 客户端
     * @author Kyle
     * Created on 2026/04/10
     */
    private void handleTextMessage(WecomReceiveMessage message, WecomLongConnectionClient client) {
        String userText = message.getTextContent();
        if (userText == null || userText.trim().isEmpty()) {
            log.warn("【消息处理】收到空文本消息，忽略");
            return;
        }

        log.info("【消息处理】用户 {} 说: {}", message.getFromUserName(), userText);

        // 调用 DeepSeek AI
        String aiReply = deepSeekService.chat(userText.trim());
        log.info("【消息处理】AI 回复: {}", aiReply);

        // 通过 WebSocket 帧回复（支持 Markdown）
        client.replyText(message.getRawFrame(), aiReply);
    }

    /**
     * 处理语音消息：使用语音转写文本调用 AI。
     *
     * @param message 语音消息（已包含转写文本）
     * @param client  WebSocket 客户端
     * @author Kyle
     * Created on 2026/04/10
     */
    private void handleVoiceMessage(WecomReceiveMessage message, WecomLongConnectionClient client) {
        String voiceText = message.getVoiceContent();
        if (voiceText == null || voiceText.trim().isEmpty()) {
            client.replyText(message.getRawFrame(), "抱歉，未能识别语音内容，请重新发送。");
            return;
        }

        log.info("【消息处理】语音转写: {}", voiceText);

        String aiReply = deepSeekService.chat(voiceText.trim());
        log.info("【消息处理】AI 回复: {}", aiReply);

        client.replyText(message.getRawFrame(), aiReply);
    }
}
