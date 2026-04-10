package com.zimu.imdemo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 企业微信机器人配置属性类。
 * 从 application.yml 中绑定 wecom 前缀的配置项。
 *
 * @author Kyle
 * Created on 2026/04/10
 */
@Component
@ConfigurationProperties(prefix = "wecom")
public class WecomProperties {

    /** 机器人配置 */
    private Bot bot = new Bot();

    public Bot getBot() {
        return bot;
    }

    public void setBot(Bot bot) {
        this.bot = bot;
    }

    /**
     * 机器人子配置（Bot ID 和 Secret）。
     */
    public static class Bot {

        /** 机器人 ID */
        private String id;

        /** 机器人 Secret */
        private String secret;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }
    }
}
