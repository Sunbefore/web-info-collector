package com.info.collector.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * RestTemplate 配置
 * 用于调用 LLM API 和企业微信 Webhook
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // 连接超时 10 秒
        factory.setConnectTimeout(10000);
        // 读取超时 180 秒（LLM 生成可能较慢，GitHub Actions海外访问更慢）
        factory.setReadTimeout(180000);
        return new RestTemplate(factory);
    }
}
