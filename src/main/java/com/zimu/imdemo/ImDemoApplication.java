package com.zimu.imdemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 企业微信机器人 + DeepSeek AI 接入服务启动类。
 * 应用启动后自动建立企业微信长连接，接收用户消息并通过 AI 进行智能回复。
 *
 * @author Kyle
 * Created on 2026/04/10
 */
@SpringBootApplication
@EnableConfigurationProperties
public class ImDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(ImDemoApplication.class, args);
    }

}
