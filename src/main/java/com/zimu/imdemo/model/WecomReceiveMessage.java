package com.zimu.imdemo.model;

import com.alibaba.fastjson.JSONObject;

/**
 * 企业微信接收消息的便捷封装类。
 * 从原始 JSON 帧中提取常用字段，方便业务层使用。
 *
 * @author Kyle
 * Created on 2026/04/10
 */
public class WecomReceiveMessage {

    /** 原始 JSON 帧（保留完整数据，方便回复时使用） */
    private final JSONObject rawFrame;

    /** 消息体 */
    private final JSONObject body;

    /**
     * 从原始 WebSocket 帧构造消息对象。
     *
     * @param rawFrame 企业微信推送的原始 JSON 帧
     * @author Kyle
     * Created on 2026/04/10
     */
    public WecomReceiveMessage(JSONObject rawFrame) {
        this.rawFrame = rawFrame;
        this.body = rawFrame.getJSONObject("body");
    }

    /**
     * 获取请求 ID（回复时必须原样回传）。
     *
     * @return 请求 ID
     * @author Kyle
     * Created on 2026/04/10
     */
    public String getReqId() {
        JSONObject headers = rawFrame.getJSONObject("headers");
        return headers != null ? headers.getString("req_id") : null;
    }

    /**
     * 获取消息类型（text / image / mixed / voice / file）。
     *
     * @return 消息类型字符串
     * @author Kyle
     * Created on 2026/04/10
     */
    public String getMsgType() {
        return body != null ? body.getString("msgtype") : null;
    }

    /**
     * 获取文本消息内容（仅 msgtype=text 时有效）。
     *
     * @return 文本内容，非文本消息返回 null
     * @author Kyle
     * Created on 2026/04/10
     */
    public String getTextContent() {
        if (body == null) {
            return null;
        }
        JSONObject text = body.getJSONObject("text");
        return text != null ? text.getString("content") : null;
    }

    /**
     * 获取语音消息的识别文本（仅 msgtype=voice 时有效）。
     *
     * @return 语音识别文本，非语音消息返回 null
     * @author Kyle
     * Created on 2026/04/10
     */
    public String getVoiceContent() {
        if (body == null) {
            return null;
        }
        JSONObject voice = body.getJSONObject("voice");
        return voice != null ? voice.getString("content") : null;
    }

    /**
     * 获取发送者用户 ID。
     *
     * @return 用户 ID
     * @author Kyle
     * Created on 2026/04/10
     */
    public String getFromUserId() {
        if (body == null) {
            return null;
        }
        JSONObject from = body.getJSONObject("from");
        return from != null ? from.getString("userid") : null;
    }

    /**
     * 获取发送者用户名称。
     *
     * @return 用户名称
     * @author Kyle
     * Created on 2026/04/10
     */
    public String getFromUserName() {
        if (body == null) {
            return null;
        }
        JSONObject from = body.getJSONObject("from");
        return from != null ? from.getString("name") : null;
    }

    /**
     * 获取会话 ID（群聊 ID 或私聊 ID）。
     *
     * @return 会话 ID
     * @author Kyle
     * Created on 2026/04/10
     */
    public String getChatId() {
        if (body == null) {
            return null;
        }
        JSONObject conversation = body.getJSONObject("conversation");
        return conversation != null ? conversation.getString("chatid") : null;
    }

    /**
     * 获取会话标题。
     *
     * @return 会话标题
     * @author Kyle
     * Created on 2026/04/10
     */
    public String getChatTitle() {
        if (body == null) {
            return null;
        }
        JSONObject conversation = body.getJSONObject("conversation");
        return conversation != null ? conversation.getString("title") : null;
    }

    /**
     * 获取原始 JSON 帧。
     *
     * @return 完整的原始帧 JSONObject
     * @author Kyle
     * Created on 2026/04/10
     */
    public JSONObject getRawFrame() {
        return rawFrame;
    }

    /**
     * 获取消息体 JSON。
     *
     * @return body 部分的 JSONObject
     * @author Kyle
     * Created on 2026/04/10
     */
    public JSONObject getBody() {
        return body;
    }
}
