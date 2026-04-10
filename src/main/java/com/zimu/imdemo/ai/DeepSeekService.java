package com.zimu.imdemo.ai;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.zimu.imdemo.config.DeepSeekProperties;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * DeepSeek AI 大模型调用服务。
 * 支持普通调用和 SSE 流式调用两种模式。
 *
 * @author Kyle
 * Created on 2026/04/10
 */
@Service
public class DeepSeekService {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekService.class);

    /** DeepSeek Chat Completions API 默认路径后缀 */
    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

    @Autowired
    private DeepSeekProperties deepSeekProperties;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();

    /**
     * 流式调用 DeepSeek API，通过回调逐步返回生成的内容片段。
     * 每收到一个 SSE delta 片段就调用 onChunk 回调。
     * 流结束后调用 onComplete 回调（参数为完整累积内容）。
     *
     * @param userMessage 用户发送的消息文本
     * @param onChunk     每个增量片段的回调（参数为 delta 文本片段）
     * @param onComplete  流式输出完成的回调（参数为完整累积内容）
     * @param onError     发生错误时的回调（参数为错误消息）
     * @author Kyle
     * Created on 2026/04/10
     */
    public void chatStream(String userMessage,
                           Consumer<String> onChunk,
                           Consumer<String> onComplete,
                           Consumer<String> onError) {
        String url = resolveUrl();

        JSONObject requestBody = buildRequestBody(userMessage);
        requestBody.put("stream", true);

        RequestBody body = RequestBody.create(
                requestBody.toJSONString(),
                MediaType.get("application/json; charset=utf-8")
        );

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + deepSeekProperties.getKey())
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .post(body)
                .build();

        try {
            Response response = httpClient.newCall(request).execute();

            if (!response.isSuccessful() || response.body() == null) {
                String errBody = response.body() != null ? response.body().string() : "";
                log.error("【DeepSeek】流式 API 调用失败，HTTP 状态码: {}, 响应: {}", response.code(), errBody);
                onError.accept("AI 服务暂时不可用，请稍后重试。");
                response.close();
                return;
            }

            // 逐行读取 SSE 流
            StringBuilder accumulated = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    // SSE 格式：data: {...}
                    if (!line.startsWith("data: ")) {
                        continue;
                    }

                    String data = line.substring(6).trim();

                    // 流结束标志
                    if ("[DONE]".equals(data)) {
                        break;
                    }

                    try {
                        JSONObject chunk = JSON.parseObject(data);
                        JSONArray choices = chunk.getJSONArray("choices");
                        if (choices == null || choices.isEmpty()) {
                            continue;
                        }

                        JSONObject delta = choices.getJSONObject(0).getJSONObject("delta");
                        if (delta == null) {
                            continue;
                        }

                        String content = delta.getString("content");
                        if (content != null && !content.isEmpty()) {
                            accumulated.append(content);
                            onChunk.accept(content);
                        }
                    } catch (Exception e) {
                        log.debug("【DeepSeek】跳过无法解析的 SSE 数据: {}", data);
                    }
                }
            }

            String fullContent = accumulated.toString().trim();
            if (fullContent.isEmpty()) {
                onError.accept("AI 未返回有效内容。");
            } else {
                onComplete.accept(fullContent);
            }

        } catch (IOException e) {
            log.error("【DeepSeek】流式 API 请求异常", e);
            onError.accept("AI 服务连接异常，请稍后重试。");
        }
    }

    /**
     * 非流式调用 DeepSeek API（保留用于简单场景）。
     *
     * @param userMessage 用户发送的消息文本
     * @return AI 生成的回复文本，失败时返回错误提示
     * @author Kyle
     * Created on 2026/04/10
     */
    public String chat(String userMessage) {
        String url = resolveUrl();
        JSONObject requestBody = buildRequestBody(userMessage);

        RequestBody body = RequestBody.create(
                requestBody.toJSONString(),
                MediaType.get("application/json; charset=utf-8")
        );

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + deepSeekProperties.getKey())
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                log.error("【DeepSeek】API 调用失败，HTTP: {}, 响应: {}", response.code(), responseBody);
                return "AI 服务暂时不可用，请稍后重试。";
            }
            return parseReply(responseBody);
        } catch (IOException e) {
            log.error("【DeepSeek】API 请求异常", e);
            return "AI 服务连接异常，请稍后重试。";
        }
    }

    /**
     * 解析 API 地址，兼容完整路径和仅 base URL 两种配置方式。
     *
     * @return 完整的 API 地址
     * @author Kyle
     * Created on 2026/04/10
     */
    private String resolveUrl() {
        String baseUrl = deepSeekProperties.getBaseUrl();
        return baseUrl.endsWith("/completions") ? baseUrl : baseUrl + CHAT_COMPLETIONS_PATH;
    }

    /**
     * 构造 DeepSeek Chat Completions API 的请求体 JSON。
     *
     * @param userMessage 用户消息内容
     * @return 构造好的请求体 JSONObject
     * @author Kyle
     * Created on 2026/04/10
     */
    private JSONObject buildRequestBody(String userMessage) {
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", deepSeekProperties.getModel());
        requestBody.put("max_tokens", deepSeekProperties.getMaxTokens());
        requestBody.put("temperature", 0.7);

        JSONArray messages = new JSONArray();

        // 系统提示词
        JSONObject systemMessage = new JSONObject();
        systemMessage.put("role", "system");
        systemMessage.put("content", deepSeekProperties.getSystemPrompt());
        messages.add(systemMessage);

        // 用户消息
        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        messages.add(userMsg);

        requestBody.put("messages", messages);
        return requestBody;
    }

    /**
     * 从非流式响应 JSON 中提取 AI 回复文本。
     *
     * @param responseBody API 响应的原始 JSON 字符串
     * @return 提取出的回复文本
     * @author Kyle
     * Created on 2026/04/10
     */
    private String parseReply(String responseBody) {
        try {
            JSONObject json = JSON.parseObject(responseBody);
            JSONArray choices = json.getJSONArray("choices");
            if (choices != null && !choices.isEmpty()) {
                JSONObject message = choices.getJSONObject(0).getJSONObject("message");
                if (message != null) {
                    String content = message.getString("content");
                    return content != null ? content.trim() : "AI 未返回有效内容。";
                }
            }
            log.error("【DeepSeek】响应结构异常: {}", responseBody);
            return "AI 响应解析失败，请重试。";
        } catch (Exception e) {
            log.error("【DeepSeek】响应解析异常", e);
            return "AI 响应解析失败，请重试。";
        }
    }
}
