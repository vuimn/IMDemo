package com.zimu.imdemo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * DeepSeek AI 配置属性类。
 * 从 application.yml 中绑定 deepseek.api 前缀的配置项。
 *
 * @author Kyle
 * Created on 2026/04/10
 */
@Component
@ConfigurationProperties(prefix = "deepseek.api")
public class DeepSeekProperties {

    /** DeepSeek API Key */
    private String key;

    /** DeepSeek API 基础地址 */
    private String baseUrl;

    /** 使用的模型名称 */
    private String model;

    /** 最大生成 Token 数 */
    private int maxTokens;

    /** 系统提示词 */
    private String systemPrompt;

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }
}
