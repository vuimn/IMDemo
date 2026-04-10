package com.zimu.imdemo.wecom;

/**
 * 企业微信 AI 机器人 WebSocket 协议命令常量。
 * 定义开发者与企业微信服务器之间的所有通信指令。
 *
 * @author Kyle
 * Created on 2026/04/10
 */
public final class WsCmd {

    private WsCmd() {
    }

    // ========== 开发者 → 企业微信 ==========

    /** 鉴权订阅（连接建立后首次发送） */
    public static final String SUBSCRIBE = "aibot_subscribe";

    /** 心跳 */
    public static final String HEARTBEAT = "ping";

    /** 回复消息 */
    public static final String RESPONSE = "aibot_respond_msg";

    /** 回复欢迎语 */
    public static final String RESPONSE_WELCOME = "aibot_respond_welcome_msg";

    /** 更新模板卡片 */
    public static final String RESPONSE_UPDATE = "aibot_respond_update_msg";

    /** 主动发送消息 */
    public static final String SEND_MSG = "aibot_send_msg";

    // ========== 企业微信 → 开发者 ==========

    /** 消息回调推送 */
    public static final String CALLBACK = "aibot_msg_callback";

    /** 事件回调推送 */
    public static final String EVENT_CALLBACK = "aibot_event_callback";
}
