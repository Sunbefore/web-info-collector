package com.info.collector.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.info.collector.config.CollectorProperties;
import com.info.collector.model.Article;
import com.info.collector.model.CollectResult;
import com.info.collector.model.SiteConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 采集编排服务（核心服务）
 * 协调 CrawlerService、LlmService、NotifyService 完成完整的采集-分析-推送流程
 */
@Slf4j
@Service
public class CollectorService {

    @Resource
    private CrawlerService crawlerService;

    @Resource
    private LlmService llmService;

    @Resource
    private NotifyService notifyService;

    @Resource
    private CollectorProperties properties;

    /** 动态管理的网站配置列表（支持运行时添加/删除） */
    private final CopyOnWriteArrayList<SiteConfig> dynamicSites = new CopyOnWriteArrayList<>();

    /**
     * 执行完整的采集流程
     * 1. 遍历所有启用的网站
     * 2. 抓取当天最新文章
     * 3. 调用 LLM 生成摘要
     * 4. 推送通知
     *
     * @param keyword    关键词（可为空）
     * @param siteNames  指定网站名称（可为空，表示全部）
     * @param sendNotify 是否发送通知
     * @return 采集结果列表
     */
    public List<CollectResult> collect(String keyword, List<String> siteNames, boolean sendNotify,
                                        String startDate, String endDate) {
        log.info("========== 开始执行资讯采集任务 ==========");
        log.info("关键词: {}，指定网站: {}，日期范围: {} ~ {}，推送通知: {}",
                StrUtil.isBlank(keyword) ? "无" : keyword,
                siteNames != null ? siteNames : "全部",
                StrUtil.isBlank(startDate) ? "近一周" : startDate,
                StrUtil.isBlank(endDate) ? startDate : endDate,
                sendNotify);

        // 解析日期范围
        Date[] dateRange = parseDateRange(startDate, endDate);

        List<CollectResult> results = new ArrayList<>();

        // 合并配置文件和动态添加的网站
        List<SiteConfig> allSites = getAllEnabledSites(siteNames);

        if (allSites.isEmpty()) {
            log.warn("没有可用的网站配置，请先添加监控网站");
            return results;
        }

        List<String> keywords = parseKeywords(keyword);

        if (!keywords.isEmpty()) {
            // 有关键词：按关键词分组输出
            results = collectByKeywords(allSites, keywords, dateRange);
        } else {
            // 无关键词：按网站分组输出
            for (SiteConfig site : allSites) {
                CollectResult result = collectFromSite(site, null, dateRange);
                results.add(result);
            }
        }

        // 发送通知
        if (sendNotify) {
            try {
                notifyService.sendNotification(results, keyword, dateRange[0], dateRange[1]);
            } catch (Exception e) {
                log.error("发送通知失败: {}", e.getMessage(), e);
            }
        }

        log.info("========== 资讯采集任务完成 ==========");
        return results;
    }

    /**
     * 从单个网站采集资讯
     */
    /**
     * 解析日期范围参数
     * @return [startDate, endDate]，未传则默认近一周
     */
    private Date[] parseDateRange(String startDate, String endDate) {
        Date start;
        Date end;

        if (StrUtil.isNotBlank(startDate)) {
            start = DateUtil.parse(startDate, "yyyy-MM-dd");
            if (StrUtil.isNotBlank(endDate)) {
                // 结束日期取当天的23:59:59
                end = DateUtil.endOfDay(DateUtil.parse(endDate, "yyyy-MM-dd"));
            } else {
                // 只传了startDate，查当天
                end = DateUtil.endOfDay(start);
            }
        } else {
            // 未传日期，默认近一周
            end = new Date();
            start = DateUtil.offsetDay(end, -7);
        }

        return new Date[]{start, end};
    }

    /**
     * 按关键词分组采集：先从所有网站抓文章，再按关键词分组生成报告
     */
    private List<CollectResult> collectByKeywords(List<SiteConfig> sites, List<String> keywords, Date[] dateRange) {
        // 1. 从所有网站抓取文章
        List<Article> allArticles = new ArrayList<>();
        for (SiteConfig site : sites) {
            try {
                List<Article> articles = crawlerService.crawlSite(site, dateRange[0], dateRange[1]);
                allArticles.addAll(articles);
            } catch (Exception e) {
                log.error("网站 [{}] 抓取失败: {}", site.getName(), e.getMessage(), e);
            }
        }

        if (allArticles.isEmpty()) {
            // 所有网站都没抓到文章，每个关键词返回空结果
            List<CollectResult> results = new ArrayList<>();
            for (String kw : keywords) {
                CollectResult result = new CollectResult();
                result.setSiteName(kw);
                result.setKeyword(kw);
                result.setCollectTime(DateUtil.format(new Date(), "yyyy-MM-dd HH:mm:ss"));
                result.setSuccess(true);
                results.add(result);
            }
            return results;
        }

        // 2. 用LLM逐篇判断与哪些关键词相关
        llmService.matchArticlesByLlm(allArticles, keywords);

        // 3. 按关键词分组
        LinkedHashMap<String, List<Article>> keywordGrouped = new LinkedHashMap<>();
        for (String kw : keywords) {
            keywordGrouped.put(kw, new ArrayList<>());
        }
        for (Article article : allArticles) {
            if (article.getMatchedKeywords() != null) {
                for (String mk : article.getMatchedKeywords()) {
                    if (keywordGrouped.containsKey(mk)) {
                        keywordGrouped.get(mk).add(article);
                    }
                }
            }
        }

        // 4. 为每个关键词生成摘要和结果
        List<CollectResult> results = new ArrayList<>();
        for (Map.Entry<String, List<Article>> entry : keywordGrouped.entrySet()) {
            String kw = entry.getKey();
            List<Article> kwArticles = entry.getValue();

            CollectResult result = new CollectResult();
            result.setSiteName(kw);
            result.setKeyword(kw);
            result.setCollectTime(DateUtil.format(new Date(), "yyyy-MM-dd HH:mm:ss"));
            result.setSuccess(true);
            result.setArticles(kwArticles);

            if (!kwArticles.isEmpty()) {
                // 为每篇文章生成摘要
                for (Article article : kwArticles) {
                    if (StrUtil.isBlank(article.getSummary())) {
                        try {
                            article.setSummary(llmService.summarizeArticle(article));
                        } catch (Exception e) {
                            log.warn("文章摘要生成失败: {} - {}", article.getTitle(), e.getMessage());
                            article.setSummary("（摘要生成失败）");
                        }
                    }
                }

                // 生成该关键词下的整体摘要
                try {
                    String overallSummary = llmService.summarizeArticles(kwArticles, kw, kw);
                    result.setOverallSummary(overallSummary);
                } catch (Exception e) {
                    log.warn("关键词 [{}] 整体摘要生成失败: {}", kw, e.getMessage());
                    result.setOverallSummary("（整体摘要生成失败）");
                }
            }

            results.add(result);
            log.info("关键词 [{}] 共 {} 篇相关文章", kw, kwArticles.size());
        }

        return results;
    }

    private CollectResult collectFromSite(SiteConfig site, String keyword, Date[] dateRange) {
        CollectResult result = new CollectResult();
        result.setSiteName(site.getName());
        result.setSiteUrl(site.getUrl());
        result.setCollectTime(DateUtil.format(new Date(), "yyyy-MM-dd HH:mm:ss"));
        result.setKeyword(keyword);

        try {
            // 1. 抓取文章（按日期范围过滤）
            List<Article> articles = crawlerService.crawlSite(site, dateRange[0], dateRange[1]);
            result.setArticles(articles);

            if (!articles.isEmpty()) {
                // 2. 为每篇文章生成摘要
                for (Article article : articles) {
                    try {
                        article.setSummary(llmService.summarizeArticle(article));
                    } catch (Exception e) {
                        log.warn("文章摘要生成失败: {} - {}", article.getTitle(), e.getMessage());
                        article.setSummary("（摘要生成失败）");
                    }
                }

                // 3. 生成整体摘要报告
                String overallSummary = llmService.summarizeArticles(articles, site.getName(), null);
                result.setOverallSummary(overallSummary);
            }

            result.setSuccess(true);
            log.info("网站 [{}] 采集完成，共 {} 篇相关文章", site.getName(), articles.size());

        } catch (Exception e) {
            result.setSuccess(false);
            result.setErrorMessage(e.getMessage());
            log.error("网站 [{}] 采集失败: {}", site.getName(), e.getMessage(), e);
        }

        return result;
    }

    /**
     * 解析关键词字符串为列表
     */
    private List<String> parseKeywords(String keyword) {
        List<String> keywords = new ArrayList<>();
        if (StrUtil.isNotBlank(keyword)) {
            for (String kw : keyword.split("[,，、\\s]+")) {
                if (StrUtil.isNotBlank(kw.trim())) {
                    keywords.add(kw.trim());
                }
            }
        }
        return keywords;
    }

    /**
     * 获取所有启用的网站配置
     * 支持按名称过滤
     */
    private List<SiteConfig> getAllEnabledSites(List<String> siteNames) {
        List<SiteConfig> allSites = new ArrayList<>();

        // 配置文件中的网站
        if (properties.getSites() != null) {
            allSites.addAll(properties.getSites());
        }

        // 动态添加的网站
        allSites.addAll(dynamicSites);

        // 过滤已启用的
        List<SiteConfig> enabledSites = new ArrayList<>();
        for (SiteConfig site : allSites) {
            if (!site.isEnabled()) {
                continue;
            }
            // 如果指定了网站名称列表，则只保留匹配的
            if (siteNames != null && !siteNames.isEmpty()) {
                if (!siteNames.contains(site.getName())) {
                    continue;
                }
            }
            enabledSites.add(site);
        }

        return enabledSites;
    }

    // ==================== 动态网站管理 ====================

    /**
     * 添加监控网站（运行时动态添加）
     *
     * @param siteConfig 网站配置
     */
    public void addSite(SiteConfig siteConfig) {
        // 检查是否已存在同名网站
        for (SiteConfig existing : dynamicSites) {
            if (existing.getName().equals(siteConfig.getName())) {
                dynamicSites.remove(existing);
                break;
            }
        }
        dynamicSites.add(siteConfig);
        log.info("动态添加监控网站: {}", siteConfig.getName());
    }

    /**
     * 移除监控网站
     *
     * @param siteName 网站名称
     * @return 是否移除成功
     */
    public boolean removeSite(String siteName) {
        boolean removed = dynamicSites.removeIf(site -> site.getName().equals(siteName));
        if (removed) {
            log.info("移除监控网站: {}", siteName);
        }
        return removed;
    }

    /**
     * 获取所有网站配置（包括配置文件和动态添加的）
     */
    public List<SiteConfig> listAllSites() {
        List<SiteConfig> allSites = new ArrayList<>();
        if (properties.getSites() != null) {
            allSites.addAll(properties.getSites());
        }
        allSites.addAll(dynamicSites);
        return allSites;
    }
}
