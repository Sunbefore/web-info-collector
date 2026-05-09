package com.info.collector.model;

import lombok.Data;

import java.util.List;

/**
 * 网站配置模型
 * 定义目标网站的抓取规则，包括 CSS 选择器、日期格式等
 * 支持两种模式：HTML解析模式 和 API模式
 */
@Data
public class SiteConfig {

    /** 网站名称（用于显示和日志） */
    private String name;

    /** 网站列表页 URL */
    private String url;

    // ========== HTML 解析模式配置 ==========

    /** 列表页中每条资讯的容器选择器 */
    private String listSelector;

    /** 标题选择器（相对于 listSelector） */
    private String titleSelector;

    /** 链接选择器（相对于 listSelector） */
    private String linkSelector;

    /** 日期选择器（相对于 listSelector） */
    private String dateSelector;

    /** 日期格式（如 yyyy-MM-dd） */
    private String dateFormat;

    /** 文章详情页正文选择器 */
    private String contentSelector;

    /** 子页面列表（HTML模式下多栏目场景，每个子页面有独立URL和名称） */
    private List<SubPage> subPages;

    /** 分页选择器（如 "a:contains(下一页)"），配置后自动翻页 */
    private String nextPageSelector;

    /** URL模式分页（如 "index_{page}.htm"），{page}从1开始递增，首页为index.htm */
    private String nextPagePattern;

    // ========== API 模式配置 ==========

    /** 是否使用API模式（直接请求JSON接口获取数据） */
    private boolean apiMode = false;

    /** API 基础地址 */
    private String apiUrl;

    /** API 返回数据中的 itemId 列表（对应不同栏目） */
    private List<ApiItem> apiItems;

    /** 文章详情页URL模板，{docId}和{itemId}会被替换 */
    private String detailUrlTemplate;

    /** 是否启用该网站采集 */
    private boolean enabled = true;

    // ========== 站点级限速配置 ==========

    /** 列表页/翻页请求间隔（毫秒），未配置则使用全局 collector.crawler.request-interval */
    private Long listRequestInterval;

    /** 详情页请求间隔（毫秒），未配置则使用全局 collector.crawler.request-interval */
    private Long detailRequestInterval;

    /** 栏目（subPage）之间间隔（毫秒），未配置则使用全局 collector.crawler.request-interval */
    private Long subPageInterval;

    /** 检测到验证码后的冷却等待时间（毫秒），默认 300000（5分钟） */
    private Long captchaCooldown = 300000L;

    /** 验证码冷却重试次数，默认 3 */
    private Integer captchaMaxRetries = 3;

    @Data
    public static class ApiItem {
        /** 栏目名称 */
        private String name;
        /** 栏目 itemId */
        private Integer itemId;
    }

    @Data
    public static class SubPage {
        /** 栏目名称 */
        private String name;
        /** 栏目列表页URL */
        private String url;
        /** JSON接口URL（配置此项时使用JSON模式获取文章列表） */
        private String jsonUrl;
        /** JSON中标题字段名，默认title */
        private String jsonTitleField;
        /** JSON中链接字段名，默认link */
        private String jsonLinkField;
        /** JSON中日期字段名，默认pubDate */
        private String jsonDateField;
    }
}
