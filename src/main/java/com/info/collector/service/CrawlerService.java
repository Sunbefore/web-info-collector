package com.info.collector.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.info.collector.config.CollectorProperties;
import com.info.collector.model.Article;
import com.info.collector.model.SiteConfig;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * 网页爬虫服务
 * 支持两种模式：
 * 1. HTML模式 - 用Jsoup解析静态HTML页面
 * 2. API模式 - 直接请求JSON接口获取数据（适用于动态渲染的网站）
 * 支持多关键词过滤，匹配标题或正文
 */
@Slf4j
@Service
public class CrawlerService {

    @Resource
    private CollectorProperties properties;

    /**
     * 抓取指定网站的文章
     * 只做时间过滤和内容抓取，关键词相关性由LLM判断
     *
     * @param startDate 起始日期
     * @param endDate   截止日期
     */
    public List<Article> crawlSite(SiteConfig siteConfig, Date startDate, Date endDate) {
        if (siteConfig.isApiMode()) {
            return crawlSiteByApi(siteConfig, startDate, endDate);
        } else {
            return crawlSiteByHtml(siteConfig, startDate, endDate);
        }
    }

    /**
     * API模式：直接请求JSON接口获取文章列表
     */
    private List<Article> crawlSiteByApi(SiteConfig siteConfig, Date startDate, Date endDate) {
        List<Article> articles = new ArrayList<>();
        CollectorProperties.CrawlerConfig crawlerConfig = properties.getCrawler();

        log.info("开始抓取网站(API模式): {}，日期范围: {} ~ {}", siteConfig.getName(),
                DateUtil.format(startDate, "yyyy-MM-dd"), DateUtil.format(endDate, "yyyy-MM-dd"));

        if (siteConfig.getApiItems() == null || siteConfig.getApiItems().isEmpty()) {
            log.warn("网站 [{}] 未配置apiItems，跳过", siteConfig.getName());
            return articles;
        }

        for (SiteConfig.ApiItem apiItem : siteConfig.getApiItems()) {
            try {
                crawlApiItemWithPaging(siteConfig, apiItem, crawlerConfig, startDate, endDate, articles);
            } catch (Exception e) {
                log.error("抓取栏目 [{}] 失败: {}", apiItem.getName(), e.getMessage(), e);
            }
        }

        log.info("网站 [{}] 抓取完成，共获取 {} 篇文章", siteConfig.getName(), articles.size());
        return articles;
    }

    /**
     * 分页请求某个栏目的文章，遇到超过一周的文章就停止翻页
     */
    private void crawlApiItemWithPaging(SiteConfig siteConfig, SiteConfig.ApiItem apiItem,
                                         CollectorProperties.CrawlerConfig crawlerConfig,
                                         Date startDate, Date endDate, List<Article> articles) throws Exception {
        int pageIndex = 1;
        int pageSize = 10;
        boolean hasMore = true;

        while (hasMore) {
            String requestUrl = siteConfig.getApiUrl() + "?itemId=" + apiItem.getItemId()
                    + "&pageSize=" + pageSize + "&pageIndex=" + pageIndex;
            log.info("请求API: {} - 第{}页 - {}", apiItem.getName(), pageIndex, requestUrl);

            String response = fetchApiListResponseWithRetry(requestUrl, crawlerConfig, apiItem.getName(), pageIndex);

            if (response == null) {
                log.warn("API请求失败，跳过当前页: {}", requestUrl);
                break;
            }

            JSONObject json = JSONObject.parseObject(response);
            if (json == null || json.getIntValue("rptCode") != 200) {
                log.warn("API返回异常: {}", response);
                break;
            }

            JSONObject data = json.getJSONObject("data");
            if (data == null) break;

            JSONArray rows = data.getJSONArray("rows");
            if (rows == null || rows.isEmpty()) break;

            boolean foundOldArticle = false;

            for (int i = 0; i < rows.size(); i++) {
                JSONObject doc = rows.getJSONObject(i);
                String title = doc.getString("docTitle");
                String publishDateStr = doc.getString("publishDate");

                if (StrUtil.isBlank(title)) continue;

                // 日期过滤：早于起始日期则停止（列表按时间倒序），晚于截止日期则跳过
                if (StrUtil.isNotBlank(publishDateStr)) {
                    try {
                        Date publishDate = DateUtil.parse(publishDateStr);
                        if (publishDate.before(startDate)) {
                            foundOldArticle = true;
                            break;
                        }
                        if (publishDate.after(endDate)) {
                            continue;
                        }
                    } catch (Exception e) {
                        log.debug("日期解析失败，保留文章: {} ({})", title, publishDateStr);
                    }
                }

                // 构建文章链接
                String link = buildArticleLink(doc, siteConfig);

                Article article = new Article();
                article.setTitle(title);
                article.setUrl(link);
                article.setPublishDate(normalizeDateStr(publishDateStr, null));
                article.setSourceSite(siteConfig.getName() + " - " + apiItem.getName());

                // 请求间隔
                Thread.sleep(crawlerConfig.getRequestInterval());

                // 获取详情页正文
                fetchArticleDetail(article, siteConfig, crawlerConfig);

                articles.add(article);
                log.info("成功抓取文章: {} ({})", title, article.getPublishDate());
            }

            // 遇到早于起始日期的文章，不再翻页
            if (foundOldArticle) {
                log.info("栏目 [{}] 已到达日期范围之前的文章，停止翻页", apiItem.getName());
                break;
            }

            // 判断是否还有下一页
            int total = data.getIntValue("total");
            if (pageIndex * pageSize >= total) {
                break;
            }

            pageIndex++;

            // 翻页间隔
            Thread.sleep(crawlerConfig.getRequestInterval());
        }
    }

    private String buildArticleLink(JSONObject doc, SiteConfig siteConfig) {
        String isTitleLink = doc.getString("isTitleLink");
        if ("1".equals(isTitleLink) && StrUtil.isNotBlank(doc.getString("titleLink"))) {
            return doc.getString("titleLink");
        }
        String docId = doc.getString("docId");
        String itemId = doc.getString("itemId");
        if (StrUtil.isNotBlank(siteConfig.getDetailUrlTemplate())) {
            return siteConfig.getDetailUrlTemplate()
                    .replace("{docId}", docId != null ? docId : "")
                    .replace("{itemId}", itemId != null ? itemId : "");
        }
        return siteConfig.getUrl();
    }

    /**
     * HTML模式：用Jsoup解析静态HTML页面
     * 支持两种场景：
     * 1. 单URL模式（原有逻辑）：直接抓取siteConfig.url
     * 2. 多栏目模式（subPages）：遍历每个子页面URL，支持分页
     */
    private List<Article> crawlSiteByHtml(SiteConfig siteConfig, Date startDate, Date endDate) {
        List<Article> articles = new ArrayList<>();
        CollectorProperties.CrawlerConfig crawlerConfig = properties.getCrawler();

        log.info("开始抓取网站(HTML模式): {}，日期范围: {} ~ {}", siteConfig.getName(),
                DateUtil.format(startDate, "yyyy-MM-dd"), DateUtil.format(endDate, "yyyy-MM-dd"));

        if (siteConfig.getSubPages() != null && !siteConfig.getSubPages().isEmpty()) {
            // 多栏目模式：遍历子页面
            for (SiteConfig.SubPage subPage : siteConfig.getSubPages()) {
                try {
                    String sourceName = siteConfig.getName() + " - " + subPage.getName();
                    List<Article> subArticles;
                    if (StrUtil.isNotBlank(subPage.getJsonUrl())) {
                        // JSON接口模式
                        subArticles = crawlJsonSubPage(subPage, siteConfig, crawlerConfig, sourceName, startDate, endDate);
                    } else {
                        // HTML列表模式
                        subArticles = crawlHtmlListPage(
                                subPage.getUrl(), siteConfig, crawlerConfig, sourceName, startDate, endDate);
                    }
                    articles.addAll(subArticles);
                } catch (Exception e) {
                    log.error("抓取栏目 [{}] 失败: {}", subPage.getName(), e.getMessage(), e);
                }
            }
        } else {
            // 单URL模式（原有逻辑）
            try {
                List<Article> pageArticles = crawlHtmlListPage(
                        siteConfig.getUrl(), siteConfig, crawlerConfig,
                        siteConfig.getName(), startDate, endDate);
                articles.addAll(pageArticles);
            } catch (Exception e) {
                log.error("抓取网站 [{}] 失败: {}", siteConfig.getName(), e.getMessage(), e);
            }
        }

        log.info("网站 [{}] 抓取完成，共获取 {} 篇文章", siteConfig.getName(), articles.size());
        return articles;
    }

    /**
     * JSON接口模式：直接请求JSON数组获取文章列表
     * 适用于返回简单JSON数组的接口（如gov.cn时政要闻）
     */
    private List<Article> crawlJsonSubPage(SiteConfig.SubPage subPage, SiteConfig siteConfig,
                                            CollectorProperties.CrawlerConfig crawlerConfig,
                                            String sourceName, Date startDate, Date endDate) throws Exception {
        List<Article> articles = new ArrayList<>();
        log.info("抓取JSON接口: {} - {}", sourceName, subPage.getJsonUrl());

        String response = retryTask(() -> HttpUtil.createGet(subPage.getJsonUrl())
                .header("User-Agent", crawlerConfig.getUserAgent())
                .timeout(crawlerConfig.getTimeout())
                .execute()
                .body(), "JSON接口请求-" + sourceName);

        if (response == null) {
            log.warn("JSON接口请求失败，跳过: {}", subPage.getJsonUrl());
            return articles;
        }

        JSONArray jsonArray = JSONArray.parseArray(response);
        if (jsonArray == null || jsonArray.isEmpty()) {
            log.warn("[{}] JSON接口返回空数据", sourceName);
            return articles;
        }

        String titleField = StrUtil.isNotBlank(subPage.getJsonTitleField()) ? subPage.getJsonTitleField() : "title";
        String linkField = StrUtil.isNotBlank(subPage.getJsonLinkField()) ? subPage.getJsonLinkField() : "link";
        String dateField = StrUtil.isNotBlank(subPage.getJsonDateField()) ? subPage.getJsonDateField() : "pubDate";

        for (int i = 0; i < jsonArray.size(); i++) {
            JSONObject item = jsonArray.getJSONObject(i);
            String title = item.getString(titleField);
            String link = item.getString(linkField);
            String dateStr = item.getString(dateField);

            if (StrUtil.isBlank(title) || StrUtil.isBlank(link)) continue;

            // 日期过滤
            String normalizedDate = normalizeDateStr(dateStr, "yyyy-MM-dd");
            if (StrUtil.isNotBlank(normalizedDate)) {
                try {
                    Date articleDate = DateUtil.parse(normalizedDate, "yyyy-MM-dd");
                    if (articleDate.before(startDate)) continue;
                    if (articleDate.after(endDate)) continue;
                } catch (Exception ignored) {}
            }

            Article article = new Article();
            article.setTitle(title);
            article.setUrl(encodeUrl(link));
            article.setPublishDate(StrUtil.isNotBlank(normalizedDate) ? normalizedDate : dateStr);
            article.setSourceSite(sourceName);

            // 获取详情页内容
            Thread.sleep(crawlerConfig.getRequestInterval());
            fetchArticleDetail(article, siteConfig, crawlerConfig);

            articles.add(article);
            log.info("成功抓取文章: {} ({})", title, article.getPublishDate());

            if (articles.size() >= crawlerConfig.getMaxArticlesPerSite()) break;
        }

        log.info("[{}] JSON接口抓取完成，共 {} 篇文章", sourceName, articles.size());
        return articles;
    }

    /**
     * 抓取单个HTML列表页（支持分页翻页）
     *
     * @param pageUrl       列表页URL
     * @param siteConfig    网站配置（选择器等）
     * @param crawlerConfig 爬虫配置
     * @param sourceName    来源名称（用于标记文章来源）
     * @param startDate     起始日期
     * @param endDate       截止日期
     */
    private List<Article> crawlHtmlListPage(String pageUrl, SiteConfig siteConfig,
                                             CollectorProperties.CrawlerConfig crawlerConfig,
                                             String sourceName, Date startDate, Date endDate) throws Exception {
        List<Article> articles = new ArrayList<>();
        String currentUrl = pageUrl;
        int pageNum = 1;
        int pageIndex = 0; // 用于URL模式翻页（index.htm=0, index_1.htm=1, ...）
        boolean hasMorePages = true;

        while (hasMorePages && currentUrl != null) {
            log.info("抓取列表页: {} (第{}页) - {}", sourceName, pageNum, currentUrl);

            final String finalCurrentUrl = currentUrl;
            Document listPage;

            listPage = retryTask(() -> Jsoup.connect(finalCurrentUrl)
                    .userAgent(crawlerConfig.getUserAgent())
                    .timeout(crawlerConfig.getTimeout())
                    .ignoreHttpErrors(true)
                    .followRedirects(true)
                    .sslSocketFactory(createTrustAllSslSocketFactory())
                    .get(), "HTML列表页请求-" + sourceName + "-第" + pageNum + "页");

            if (listPage == null) {
                log.warn("列表页请求失败，跳过: {}", currentUrl);
                break;
            }

            Elements listItems = listPage.select(siteConfig.getListSelector());
            if (listItems.isEmpty()) {
                listItems = findFallbackListItems(listPage, currentUrl);
                if (!listItems.isEmpty()) {
                    log.info("[{}] 主选择器未命中，启用兜底列表提取，找到 {} 条候选项", sourceName, listItems.size());
                }
            }
            log.info("[{}] 第{}页找到 {} 条列表项", sourceName, pageNum, listItems.size());

            if (listItems.isEmpty()) {
                logEmptyListPageDiagnostic(listPage, sourceName, pageNum, currentUrl, siteConfig.getListSelector());
                for (int retry = 1; retry <= 2 && listItems.isEmpty(); retry++) {
                    log.warn("[{}] 第{}页列表项为 0，开始第{}次补充重试: {}", sourceName, pageNum, retry, currentUrl);
                    sleepQuietly(1000L * retry);
                    listPage = retryTask(() -> Jsoup.connect(finalCurrentUrl)
                            .userAgent(crawlerConfig.getUserAgent())
                            .timeout(crawlerConfig.getTimeout())
                            .ignoreHttpErrors(true)
                            .followRedirects(true)
                            .sslSocketFactory(createTrustAllSslSocketFactory())
                            .get(), "HTML列表页补充重试-" + sourceName + "-第" + pageNum + "页-第" + retry + "次");
                    if (listPage == null) {
                        log.warn("[{}] 第{}页第{}次补充重试失败，未获取到页面", sourceName, pageNum, retry);
                        continue;
                    }
                    listItems = listPage.select(siteConfig.getListSelector());
                    if (listItems.isEmpty()) {
                        listItems = findFallbackListItems(listPage, currentUrl);
                    }
                    if (listItems.isEmpty()) {
                        logEmptyListPageDiagnostic(listPage, sourceName, pageNum, currentUrl, siteConfig.getListSelector());
                    } else {
                        log.info("[{}] 第{}页第{}次补充重试成功，找到 {} 条列表项", sourceName, pageNum, retry, listItems.size());
                    }
                }
            }

            if (listItems.isEmpty()) break;

            boolean foundOldArticle = false;
            for (Element item : listItems) {
                if (articles.size() >= crawlerConfig.getMaxArticlesPerSite()) {
                    log.info("[{}] 已达到最大抓取数 {}", sourceName, crawlerConfig.getMaxArticlesPerSite());
                    hasMorePages = false;
                    break;
                }

                try {
                    Article article = parseHtmlListItem(item, siteConfig, sourceName);
                    if (article == null) continue;

                    // 日期过滤
                    if (StrUtil.isNotBlank(article.getPublishDate())) {
                        try {
                            Date articleDate = DateUtil.parse(article.getPublishDate(), "yyyy-MM-dd");
                            if (articleDate.before(startDate)) {
                                foundOldArticle = true;
                                break;
                            }
                            if (articleDate.after(endDate)) {
                                continue;
                            }
                        } catch (Exception e) {
                            log.debug("日期解析失败，保留文章: {} ({})", article.getTitle(), article.getPublishDate());
                        }
                    }

                    Thread.sleep(crawlerConfig.getRequestInterval());

                    // PDF链接：下载并提取文字内容
                    if (isDocumentUrl(article.getUrl())) {
                        article.getDocumentUrls().add(article.getUrl());
                        fetchPdfContent(article);
                    } else {
                        fetchArticleDetail(article, siteConfig, crawlerConfig);
                    }

                    articles.add(article);
                    log.info("成功抓取文章: {} ({})", article.getTitle(), article.getPublishDate());

                } catch (Exception e) {
                    log.warn("解析列表项失败，跳过: {}", e.getMessage());
                }
            }

            // 遇到早于起始日期的文章，停止翻页
            if (foundOldArticle) {
                log.info("[{}] 已到达日期范围之前的文章，停止翻页", sourceName);
                break;
            }

            // 检查是否有下一页
            if (StrUtil.isNotBlank(siteConfig.getNextPagePattern())) {
                // URL模式翻页：index.htm -> index_1.htm -> index_2.htm
                pageIndex++;
                currentUrl = buildNextPageUrl(pageUrl, siteConfig.getNextPagePattern(), pageIndex);
            } else {
                currentUrl = findNextPageUrl(listPage, siteConfig, currentUrl);
            }
            if (currentUrl == null) {
                hasMorePages = false;
            } else {
                pageNum++;
                Thread.sleep(crawlerConfig.getRequestInterval());
            }
        }

        return articles;
    }

    /**
     * 解析单个HTML列表项为Article对象
     */
    private void logEmptyListPageDiagnostic(Document listPage, String sourceName, int pageNum,
                                            String currentUrl, String listSelector) {
        if (listPage == null) {
            log.warn("[{}] 第{}页空列表诊断: 页面对象为空, url={}", sourceName, pageNum, currentUrl);
            return;
        }
        String title = StrUtil.blankToDefault(listPage.title(), "(empty)");
        String location = StrUtil.blankToDefault(listPage.location(), currentUrl);
        String html = listPage.outerHtml();
        String snippet = html.length() > 500 ? html.substring(0, 500) : html;
        snippet = snippet.replaceAll("\\s+", " ").trim();
        log.warn("[{}] 第{}页空列表诊断: selector={}, title={}, location={}, htmlSnippet={}",
                sourceName, pageNum, listSelector, title, location, snippet);
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String fetchApiListResponseWithRetry(String requestUrl,
                                                 CollectorProperties.CrawlerConfig crawlerConfig,
                                                 String apiItemName, int pageIndex) {
        final String finalRequestUrl = requestUrl;
        String taskName = "API列表请求-" + apiItemName + "-第" + pageIndex + "页";
        String response = retryTask(() -> HttpUtil.createGet(finalRequestUrl)
                .header("User-Agent", crawlerConfig.getUserAgent())
                .timeout(crawlerConfig.getTimeout())
                .execute()
                .body(), taskName);

        for (int retry = 1; retry <= 2 && !isSuccessfulApiResponse(response); retry++) {
            log.warn("[{}] 第{}页 API未正常返回，开始第{}次补充重试: {}", apiItemName, pageIndex, retry, requestUrl);
            sleepQuietly(1000L * retry);
            response = retryTask(() -> HttpUtil.createGet(finalRequestUrl)
                    .header("User-Agent", crawlerConfig.getUserAgent())
                    .timeout(crawlerConfig.getTimeout())
                    .execute()
                    .body(), taskName + "-补充重试-" + retry);
        }

        return response;
    }

    private boolean isSuccessfulApiResponse(String response) {
        if (StrUtil.isBlank(response)) {
            return false;
        }
        try {
            JSONObject json = JSONObject.parseObject(response);
            return json != null && json.getIntValue("rptCode") == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private Article parseHtmlListItem(Element item, SiteConfig siteConfig, String sourceName) {
        Element titleEl = item.selectFirst(siteConfig.getTitleSelector());
        if (titleEl == null) {
            titleEl = findBestArticleLink(item);
        }
        if (titleEl == null) return null;
        String title = titleEl.text().trim();

        Element linkEl = item.selectFirst(siteConfig.getLinkSelector());
        if (linkEl == null) {
            linkEl = titleEl;
        }
        String link = linkEl != null ? linkEl.absUrl("href") : "";
        if (StrUtil.isBlank(link)) return null;

        Element dateEl = item.selectFirst(siteConfig.getDateSelector());
        String dateStr = dateEl != null ? dateEl.text().trim() : "";
        String normalizedDate = normalizeDateStr(dateStr, siteConfig.getDateFormat());
        if (StrUtil.isBlank(normalizedDate)) {
            normalizedDate = normalizeDateStr(item.text(), null);
        }

        Article article = new Article();
        article.setTitle(title);
        article.setUrl(encodeUrl(link));
        article.setPublishDate(StrUtil.isNotBlank(normalizedDate) ? normalizedDate : dateStr);
        article.setSourceSite(sourceName);
        return article;
    }

    private Elements findFallbackListItems(Document page, String currentUrl) {
        Elements containers = new Elements();
        Set<String> seenLinks = new LinkedHashSet<>();

        for (Element link : page.select("a[href]")) {
            String href = link.absUrl("href");
            String title = link.text().trim();
            if (!isLikelyArticleLink(title, href, currentUrl)) {
                continue;
            }

            Element container = findArticleContainer(link);
            if (StrUtil.isBlank(normalizeDateStr(container.text(), null))) {
                continue;
            }
            if (seenLinks.add(href)) {
                containers.add(container);
            }
        }

        return containers;
    }

    private Element findArticleContainer(Element link) {
        Element current = link;
        for (int i = 0; i < 4 && current.parent() != null; i++) {
            Element parent = current.parent();
            String parentText = parent.text();
            if (parentText.contains(link.text())
                    && StrUtil.isNotBlank(normalizeDateStr(parentText, null))
                    && parentText.length() <= 300) {
                current = parent;
            } else {
                break;
            }
        }
        return current;
    }

    private Element findBestArticleLink(Element container) {
        Element best = null;
        for (Element link : container.select("a[href]")) {
            String title = link.text().trim();
            String href = link.absUrl("href");
            if (!isLikelyArticleLink(title, href, null)) {
                continue;
            }
            if (best == null || title.length() > best.text().trim().length()) {
                best = link;
            }
        }
        return best;
    }

    private boolean isLikelyArticleLink(String title, String href, String currentUrl) {
        if (StrUtil.isBlank(title) || StrUtil.isBlank(href)) {
            return false;
        }
        if (title.length() < 4) {
            return false;
        }
        String normalizedHref = href.toLowerCase();
        if (normalizedHref.startsWith("javascript:") || normalizedHref.startsWith("mailto:")) {
            return false;
        }
        if (StrUtil.isNotBlank(currentUrl) && href.equals(currentUrl)) {
            return false;
        }
        for (String navText : new String[]{"首页", "上一页", "下一页", "尾页", "打印本页", "关闭窗口", "网站地图", "高级搜索", "English Version", "无障碍浏览", "术语表"}) {
            if (title.contains(navText)) {
                return false;
            }
        }
        try {
            URI uri = URI.create(href);
            String path = uri.getPath();
            if (StrUtil.isBlank(path)) {
                return false;
            }
            return path.endsWith(".html") || path.endsWith(".htm") || isDocumentUrl(path);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 根据URL模式构建下一页URL
     * 例如 baseUrl=http://xxx/caizhengxinwen/, pattern=index_{page}.htm
     * pageIndex=1 -> http://xxx/caizhengxinwen/index_1.htm
     */
    private String buildNextPageUrl(String baseUrl, String pattern, int pageIndex) {
        String pageFile = pattern.replace("{page}", String.valueOf(pageIndex));
        // baseUrl 可能以 / 结尾或以 index.htm 结尾
        if (baseUrl.endsWith("/")) {
            return baseUrl + pageFile;
        } else {
            // 替换最后一个文件名
            return baseUrl.replaceAll("[^/]*$", pageFile);
        }
    }

    /**
     * 查找下一页的URL
     * 支持两种方式：
     * 1. 配置了 nextPageSelector：用选择器查找下一页链接
     * 2. 未配置：不翻页（兼容原有单页模式）
     */
    private String findNextPageUrl(Document page, SiteConfig siteConfig, String currentUrl) {
        if (StrUtil.isBlank(siteConfig.getNextPageSelector())) {
            return null;
        }
        Element nextLink = page.selectFirst(siteConfig.getNextPageSelector());
        if (nextLink == null) return null;

        // 优先取 href，如果没有则取 tagname 属性（人行网站用 tagname 存储分页URL）
        String nextUrl = nextLink.absUrl("href");
        if (StrUtil.isBlank(nextUrl) || nextUrl.equals(currentUrl)) {
            String tagname = nextLink.attr("tagname");
            if (StrUtil.isNotBlank(tagname)) {
                nextUrl = nextLink.absUrl("tagname");
                if (StrUtil.isBlank(nextUrl)) {
                    // absUrl 可能对 tagname 无效，手动拼接
                    nextUrl = page.baseUri().replaceAll("/[^/]*$", "") + "/../" + tagname;
                    try {
                        nextUrl = new java.net.URI(nextUrl).normalize().toString();
                    } catch (Exception ignored) {
                        nextUrl = "https://www.pbc.gov.cn" + tagname;
                    }
                }
            }
        }

        // 防止死循环：下一页URL不能和当前页相同
        if (StrUtil.isBlank(nextUrl) || nextUrl.equals(currentUrl)) {
            return null;
        }
        return nextUrl;
    }

    /**
     * 获取文章详情页内容
     */
    private void fetchArticleDetail(Article article, SiteConfig siteConfig,
                                     CollectorProperties.CrawlerConfig crawlerConfig) {
        Document detailPage;

        detailPage = retryTask(() -> Jsoup.connect(article.getUrl())
                .userAgent(crawlerConfig.getUserAgent())
                .timeout(crawlerConfig.getTimeout())
                .ignoreHttpErrors(true)
                .followRedirects(true)
                .sslSocketFactory(createTrustAllSslSocketFactory())
                .get(), "文章详情-" + article.getTitle());

        if (detailPage == null) {
            log.warn("获取文章详情失败: {}", article.getTitle());
            article.setContent("（详情获取失败）");
            return;
        }

        if (StrUtil.isNotBlank(siteConfig.getContentSelector())) {
            Element contentEl = detailPage.selectFirst(siteConfig.getContentSelector());
            // 备用选择器：当主选择器找不到时尝试常见的内容区域
            if (contentEl == null) {
                for (String fallback : new String[]{"#UCAP-CONTENT", ".TRS_Editor", ".Article_61 .content", ".new_text span#ReportIDtext", "#zoom", ".article-content", ".detail-content"}) {
                    contentEl = detailPage.selectFirst(fallback);
                    if (contentEl != null) break;
                }
            }
            if (contentEl != null) {
                article.setContent(contentEl.text());

                Elements images = contentEl.select("img");
                for (Element img : images) {
                    String imgUrl = img.absUrl("src");
                    if (StrUtil.isNotBlank(imgUrl)) {
                        article.getImageUrls().add(imgUrl);
                    }
                }

                Elements links = contentEl.select("a[href]");
                for (Element link : links) {
                    String href = link.absUrl("href");
                    if (isDocumentUrl(href)) {
                        article.getDocumentUrls().add(encodeUrl(href));
                    }
                }
            } else {
                String bodyText = detailPage.body() != null ? detailPage.body().text() : "";
                article.setContent(bodyText.length() > 2000 ? bodyText.substring(0, 2000) : bodyText);
            }
        } else {
            String bodyText = detailPage.body() != null ? detailPage.body().text() : "";
            article.setContent(bodyText.length() > 2000 ? bodyText.substring(0, 2000) : bodyText);
        }
    }

    /**
     * 标准化日期字符串为 yyyy-MM-dd 格式
     */
    private String normalizeDateStr(String dateStr, String dateFormat) {
        if (StrUtil.isBlank(dateStr)) {
            return "";
        }
        try {
            if (StrUtil.isNotBlank(dateFormat)) {
                Date date = DateUtil.parse(dateStr.trim(), dateFormat);
                return DateUtil.format(date, "yyyy-MM-dd");
            }
        } catch (Exception ignored) {
        }

        try {
            Date date = DateUtil.parse(dateStr.trim());
            return DateUtil.format(date, "yyyy-MM-dd");
        } catch (Exception ignored) {
        }

        String regex = "(\\d{4}[-/年]\\d{1,2}[-/月]\\d{1,2})";
        List<String> matches = cn.hutool.core.util.ReUtil.findAll(regex, dateStr, 1);
        if (!matches.isEmpty()) {
            try {
                String matched = matches.get(0).replace("年", "-").replace("月", "-").replace("/", "-");
                Date date = DateUtil.parse(matched, "yyyy-MM-dd");
                return DateUtil.format(date, "yyyy-MM-dd");
            } catch (Exception ignored) {
            }
        }

        return "";
    }

    /**
     * 创建信任所有证书的SSLSocketFactory（解决政府网站SSL证书不被Java默认信任的问题）
     */
    private javax.net.ssl.SSLSocketFactory createTrustAllSslSocketFactory() {
        try {
            javax.net.ssl.TrustManager[] trustAllCerts = new javax.net.ssl.TrustManager[]{
                    new javax.net.ssl.X509TrustManager() {
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
                        public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                        public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                    }
            };
            javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            return sc.getSocketFactory();
        } catch (Exception e) {
            throw new RuntimeException("创建SSLSocketFactory失败", e);
        }
    }

    /**
     * 下载PDF并提取文字内容
     */
    private void fetchPdfContent(Article article) {
        log.info("下载PDF并提取文字: {}", article.getUrl());

        Boolean success = retryTask(() -> {
            javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(createTrustAllSslSocketFactory());
            javax.net.ssl.HttpsURLConnection.setDefaultHostnameVerifier((h, s) -> true);

            java.net.URL url = new java.net.URL(article.getUrl());
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", properties.getCrawler().getUserAgent());
            conn.setConnectTimeout(properties.getCrawler().getTimeout());
            conn.setReadTimeout(30000);

            try (java.io.InputStream is = conn.getInputStream();
                 PDDocument doc = PDDocument.load(is)) {
                PDFTextStripper stripper = new PDFTextStripper();
                // 最多提取前10页，避免超大PDF
                stripper.setEndPage(Math.min(doc.getNumberOfPages(), 10));
                String text = stripper.getText(doc);
                if (StrUtil.isNotBlank(text)) {
                    article.setContent(text.trim());
                    log.info("PDF文字提取成功，共 {} 字", text.length());
                } else {
                    article.setContent("（PDF为扫描版，无法提取文字）");
                }
            }
            return true;
        }, "PDF下载-" + article.getTitle());

        if (success == null) {
            article.setContent("（PDF文字提取失败）");
        }
    }

    /**
     * 对URL中的中文和空格进行编码，保留已编码的部分
     */
    private String encodeUrl(String url) {
        if (StrUtil.isBlank(url)) return url;
        try {
            java.net.URI uri = new java.net.URI(url);
            // 如果能正常解析说明已经是合法URL
            if (uri.getRawPath() != null && !uri.getRawPath().contains(" ")) {
                return url;
            }
        } catch (Exception ignored) {
        }
        try {
            // 按路径段逐段编码
            java.net.URL u = new java.net.URL(url);
            return new java.net.URI(u.getProtocol(), u.getUserInfo(), u.getHost(),
                    u.getPort(), u.getPath(), u.getQuery(), u.getRef()).toASCIIString();
        } catch (Exception e) {
            // fallback: 简单替换空格
            return url.replace(" ", "%20");
        }
    }

    private boolean isDocumentUrl(String url) {
        if (StrUtil.isBlank(url)) {
            return false;
        }
        String lower = url.toLowerCase();
        return lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".docx")
                || lower.endsWith(".xls") || lower.endsWith(".xlsx") || lower.endsWith(".ppt")
                || lower.endsWith(".pptx") || lower.endsWith(".zip") || lower.endsWith(".rar");
    }

    /**
     * 通用重试方法，最多重试3次
     *
     * @param task      要执行的任务
     * @param taskName  任务名称（用于日志）
     * @param <T>       返回类型
     * @return 任务结果，失败返回null
     */
    private <T> T retryTask(Callable<T> task, String taskName) {
        final int maxRetries = 3;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return task.call();
            } catch (Exception e) {
                lastException = e;
                if (attempt < maxRetries) {
                    log.warn("[{}] 第{}次尝试失败: {}，准备重试...", taskName, attempt, e.getMessage());
                    try {
                        Thread.sleep(1000 * attempt); // 递增等待时间：1s, 2s, 3s
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        log.error("[{}] 重试{}次后仍然失败: {}", taskName, maxRetries, lastException != null ? lastException.getMessage() : "未知错误");
        return null;
    }
}
