package com.info.collector.config;

import com.info.collector.model.SiteConfig;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 采集系统配置属性
 * 从 application.yml 中读取 collector.* 前缀的配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "collector")
public class CollectorProperties {

    /** LLM 配置 */
    private LlmConfig llm = new LlmConfig();

    /** 通知配置 */
    private NotifyConfig notify = new NotifyConfig();

    /** 定时任务配置 */
    private ScheduleConfig schedule = new ScheduleConfig();

    /** 爬虫配置 */
    private CrawlerConfig crawler = new CrawlerConfig();

    /** 监控的网站列表 */
    private List<SiteConfig> sites = new ArrayList<>();

    // ==================== 内部配置类 ====================

    @Data
    public static class LlmConfig {
        /** 模型提供商 (qwen / glm) */
        private String provider = "qwen";
        /** LLM API 地址（通义千问 DashScope 兼容接口） */
        private String apiUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
        /** API Key（阿里云百炼平台） */
        private String apiKey = "sk-your-dashscope-api-key";
        /** 模型名称（qwen-turbo / qwen-plus / qwen-max） */
        private String model = "qwen-plus";
        /** 最大输出 token 数 */
        private int maxTokens = 4096;
        /** 温度参数 */
        private double temperature = 0.3;

        // ==================== GLM 配置预设 ====================
        /** GLM API 地址（智谱 AI） */
        private String glmApiUrl = "https://open.bigmodel.cn/api/paas/v4/chat/completions";
        /** GLM API Key（智谱 AI 平台） */
        private String glmApiKey;
        /** GLM 模型名称（glm-5-plus / glm-4-plus） */
        private String glmModel = "glm-5-plus";
    }

    @Data
    public static class NotifyConfig {
        /** 邮件收件人（多个用逗号分隔） */
        private String mailTo;
        /** 企业微信机器人 Webhook */
        private String wechatWebhook;
        /** PushPlus Token（个人微信推送） */
        private String pushplusToken;
    }

    @Data
    public static class ScheduleConfig {
        /** Cron 表达式 */
        private String cron = "0 30 8 * * ?";
    }

    @Data
    public static class CrawlerConfig {
        /** 请求超时时间（毫秒） */
        private int timeout = 15000;
        /** 请求间隔（毫秒） */
        private long requestInterval = 2000;
        /** 每个网站最大抓取文章数 */
        private int maxArticlesPerSite = 20;
        /** User-Agent */
        private String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    }
}
