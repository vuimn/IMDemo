package com.zimu.imdemo.wecom;

import java.util.Random;

/**
 * WebSocket 协议工具类。
 * 提供生成请求 ID 等通用方法。
 *
 * @author Kyle
 * Created on 2026/04/10
 */
public final class WsUtils {

    private static final Random RANDOM = new Random();
    private static final String HEX_CHARS = "0123456789abcdef";

    private WsUtils() {
    }

    /**
     * 生成唯一请求 ID，格式：{prefix}_{timestamp}_{8位随机hex}。
     * 用于标识每一帧消息，回复时需原样回传。
     *
     * @param prefix 请求 ID 前缀（如 "ping"、"stream"、"aibot_subscribe"）
     * @return 唯一请求 ID 字符串
     * @author Kyle
     * Created on 2026/04/10
     */
    public static String generateReqId(String prefix) {
        long timestamp = System.currentTimeMillis();
        String randomStr = generateRandomHex(8);
        return prefix + "_" + timestamp + "_" + randomStr;
    }

    /**
     * 生成指定长度的随机十六进制字符串。
     *
     * @param length 字符串长度
     * @return 随机 hex 字符串
     * @author Kyle
     * Created on 2026/04/10
     */
    private static String generateRandomHex(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(HEX_CHARS.charAt(RANDOM.nextInt(HEX_CHARS.length())));
        }
        return sb.toString();
    }
}
