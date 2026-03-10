package com.info.collector.service;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.info.collector.config.CollectorProperties;
import com.info.collector.model.Article;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * LLM 大模型服务
 * 负责调用大模型对采集到的文章进行智能摘要和分析
 * 兼容 OpenAI Chat Completions 接口格式（可对接 Ollama / 通义千问 / ChatGLM 等）
 */
@Slf4j
@Service
public class LlmService {

    @Resource
    private RestTemplate restTemplate;

    @Resource
    private CollectorProperties properties;

    /**
     * 为单篇文章生成摘要
     *
     * @param article 文章对象
     * @return 摘要文本
     */
    public String summarizeArticle(Article article) {
        String prompt = buildArticleSummaryPrompt(article);
        return callLlm(prompt, "单篇摘要 - " + article.getTitle());
    }

    /**
     * 逐篇判断文章与关键词的相关性（由LLM语义判断）
     * 传入完整正文，保证判断准确性
     *
     * @param articles 待判断的文章列表
     * @param keywords 关键词列表
     */
    public void matchArticlesByLlm(List<Article> articles, List<String> keywords) {
        if (articles.isEmpty() || keywords.isEmpty()) {
            return;
        }

        for (Article article : articles) {
            try {
                List<String> matched = matchSingleArticle(article, keywords);
                article.setMatchedKeywords(matched);
                log.info("文章相关性判断: {}，匹配: {}", article.getTitle(),
                        matched.isEmpty() ? "无" : matched);
            } catch (Exception e) {
                log.warn("文章相关性判断失败: {} - {}", article.getTitle(), e.getMessage());
            }
        }
    }

    private List<String> matchSingleArticle(Article article, List<String> keywords) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一位专业的金融监管资讯分析师。请判断以下文章与哪些关键词主题相关。\n\n");
        sb.append("关键词列表：").append(keywords).append("\n\n");
        sb.append("判断标准：只要文章内容与该关键词相关即算匹配，包括直接讨论、政策涉及、案例提及等。不要求是文章的核心主题，只要有实质性关联即可。\n\n");
        sb.append("【文章标题】").append(article.getTitle()).append("\n");
        sb.append("【文章正文】\n");

        String content = article.getContent();
        if (content != null && content.length() > 3000) {
            content = content.substring(0, 3000) + "...（已截断）";
        }
        sb.append(content != null ? content : "（无正文）").append("\n\n");

        sb.append("请只返回匹配到的关键词，用JSON数组格式，不要输出其他任何内容。\n");
        sb.append("例如：[\"反洗钱\", \"模型管控\"]\n");
        sb.append("如果都不相关，返回：[]");

        String result = callLlm(sb.toString(), "关键词匹配 - " + article.getTitle());

        // 解析结果
        List<String> matched = new ArrayList<>();
        try {
            int start = result.indexOf('[');
            int end = result.lastIndexOf(']');
            if (start >= 0 && end >= 0) {
                JSONArray array = JSON.parseArray(result.substring(start, end + 1));
                for (int i = 0; i < array.size(); i++) {
                    matched.add(array.getString(i));
                }
            }
        } catch (Exception e) {
            log.warn("解析LLM相关性结果失败: {}", e.getMessage());
        }
        return matched;
    }

    /**
     * 为一组文章生成整体摘要报告
     *
     * @param articles 文章列表
     * @param siteName 网站名称
     * @param keyword  关键词（可为空）
     * @return 整体摘要报告
     */
    public String summarizeArticles(List<Article> articles, String siteName, String keyword) {
        if (articles.isEmpty()) {
            return "当日暂无相关资讯。";
        }
        String prompt = buildOverallSummaryPrompt(articles, siteName, keyword);
        String result = callLlm(prompt, "整体摘要 - " + siteName + "（" + articles.size() + "篇）");
        // 将LLM输出中的 {{LINK_编号}} 占位符替换为真实链接
        result = replaceLinkPlaceholders(result, articles);
        return result;
    }

    /**
     * 将LLM输出中的 {{LINK_编号}} 替换为真实的Markdown链接
     * 按来源分组后的顺序，与prompt中的编号保持一致
     */
    private String replaceLinkPlaceholders(String summary, List<Article> articles) {
        // 按来源分组，保持和prompt中相同的遍历顺序
        java.util.LinkedHashMap<String, List<Article>> sourceGrouped = new java.util.LinkedHashMap<>();
        for (Article article : articles) {
            String source = article.getSourceSite() != null ? article.getSourceSite() : "其他";
            sourceGrouped.computeIfAbsent(source, k -> new ArrayList<>()).add(article);
        }
        int globalIndex = 1;
        for (List<Article> group : sourceGrouped.values()) {
            for (Article article : group) {
                String placeholder = "{{LINK_" + globalIndex + "}}";
                String link = "[查看原文](" + article.getUrl() + ")";
                summary = summary.replace(placeholder, link);
                globalIndex++;
            }
        }
        return summary;
    }

    /**
     * 构建单篇文章摘要的 Prompt
     */
    private String buildArticleSummaryPrompt(Article article) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一位专业的金融监管资讯分析师。请对以下文章进行简洁摘要，提炼关键要点。\n\n");
        sb.append("【文章标题】").append(article.getTitle()).append("\n");
        sb.append("【发布日期】").append(article.getPublishDate()).append("\n");
        sb.append("【文章来源】").append(article.getSourceSite()).append("\n");
        sb.append("【文章正文】\n");

        // 限制正文长度，避免超过模型上下文窗口
        String content = article.getContent();
        if (content != null && content.length() > 3000) {
            content = content.substring(0, 3000) + "...（已截断）";
        }
        sb.append(content).append("\n\n");

        sb.append("请用中文输出，格式要求：\n");
        sb.append("1. 一句话概述（不超过50字）\n");
        sb.append("2. 核心要点（3-5条，每条不超过30字）\n");
        sb.append("3. 如正文中包含具体的政策条款、监管要求或合规细则，请特别标注。如果正文内容不完整或仅为网页模板文字，则不要强行提示合规关注点\n");

        return sb.toString();
    }

    /**
     * 构建整体摘要报告的 Prompt
     */
    private String buildOverallSummaryPrompt(List<Article> articles, String groupName, String keyword) {
        StringBuilder sb = new StringBuilder();
        boolean hasKeyword = StrUtil.isNotBlank(keyword);

        if (hasKeyword) {
            sb.append("你是一位专业的金融监管资讯分析师。以下是与主题「").append(groupName).append("」相关的近期资讯，请进行汇总分析。\n\n");
        } else {
            sb.append("你是一位专业的金融监管资讯分析师。请对以下来自「").append(groupName).append("」的近期资讯进行汇总分析。\n\n");
        }

        // 按来源栏目分组展示文章
        java.util.LinkedHashMap<String, List<Article>> sourceGrouped = new java.util.LinkedHashMap<>();
        for (Article article : articles) {
            String source = article.getSourceSite() != null ? article.getSourceSite() : "其他";
            sourceGrouped.computeIfAbsent(source, k -> new ArrayList<>()).add(article);
        }

        sb.append("共有 ").append(articles.size()).append(" 篇资讯：\n\n");

        int globalIndex = 1;
        for (java.util.Map.Entry<String, List<Article>> entry : sourceGrouped.entrySet()) {
            sb.append("========== ").append(entry.getKey()).append(" ==========\n\n");
            List<Article> groupArticles = entry.getValue();
            for (Article article : groupArticles) {
                appendArticleToPrompt(sb, article, globalIndex++);
            }
        }

        // 输出格式要求
        sb.append("请用中文输出，格式要求如下（使用 Markdown 格式）：\n\n");
        sb.append("## 📋 要闻概览\n");
        sb.append("（用一段话概括主要动态，100字以内）\n\n");

        sb.append("然后逐条列出文章，格式（严格按此格式，不要自己编写链接）：\n");
        sb.append("- **标题** - 一句话摘要\n");
        sb.append("  来源：xxx | {{LINK_编号}}\n");
        sb.append("其中 {{LINK_编号}} 必须原样输出，编号对应文章序号，例如第1篇就写 {{LINK_1}}，第2篇写 {{LINK_2}}，以此类推。\n\n");

        sb.append("最后附加：\n");
        sb.append("## ⚠️ 合规关注点\n");
        sb.append("（仅当文章正文中包含具体的政策条款、监管细则、合规要求时才输出此部分，需注明出处文章标题。如果文章正文内容不完整、仅为网页模板文字或无法提取到实质性政策信息，则输出\"暂无需特别关注的合规事项\"即可，不要猜测或编造）\n");

        return sb.toString();
    }

    private void appendArticleToPrompt(StringBuilder sb, Article article, int index) {
        sb.append("第 ").append(index).append(" 篇：\n");
        sb.append("标题：").append(article.getTitle()).append("\n");
        sb.append("来源：").append(article.getSourceSite()).append("\n");
        sb.append("日期：").append(article.getPublishDate()).append("\n");

        String content = article.getContent();
        if (content != null && content.length() > 1500) {
            content = content.substring(0, 1500) + "...";
        }
        sb.append("正文：").append(content != null ? content : "（无正文）").append("\n\n");
    }

    /**
     * 调用 LLM API（兼容 OpenAI Chat Completions 格式）
     *
     * @param prompt 用户 Prompt
     * @return LLM 回复内容
     */
    private String callLlm(String prompt, String purpose) {
        CollectorProperties.LlmConfig llmConfig = properties.getLlm();

        try {
            // 构建请求体
            JSONObject requestBody = new JSONObject();
            requestBody.put("model", llmConfig.getModel());
            requestBody.put("max_tokens", llmConfig.getMaxTokens());
            requestBody.put("temperature", llmConfig.getTemperature());

            JSONArray messages = new JSONArray();
            JSONObject systemMsg = new JSONObject();
            systemMsg.put("role", "system");
            systemMsg.put("content", "你是一位专业的金融监管资讯分析师，擅长对政策文件、监管公告进行精准摘要和风险提示。请使用中文回答。");
            messages.add(systemMsg);

            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", prompt);
            messages.add(userMsg);

            requestBody.put("messages", messages);

            // 关闭深度思考
            JSONObject extraBody = new JSONObject();
            extraBody.put("enable_thinking", false);
            requestBody.put("extra_body", extraBody);

            // 设置请求头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (StrUtil.isNotBlank(llmConfig.getApiKey())) {
                headers.set("Authorization", "Bearer " + llmConfig.getApiKey());
            }

            HttpEntity<String> entity = new HttpEntity<>(requestBody.toJSONString(), headers);

            // 最多重试3次
            int maxRetries = 3;
            for (int retry = 0; retry < maxRetries; retry++) {
                try {
                    log.info("调用 LLM [{}]{}", purpose, retry > 0 ? " (第" + (retry + 1) + "次重试)" : "");
                    ResponseEntity<String> response = restTemplate.exchange(
                            llmConfig.getApiUrl(), HttpMethod.POST, entity, String.class);

                    if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                        JSONObject result = JSON.parseObject(response.getBody());
                        JSONArray choices = result.getJSONArray("choices");
                        if (choices != null && !choices.isEmpty()) {
                            return choices.getJSONObject(0)
                                    .getJSONObject("message")
                                    .getString("content");
                        }
                    }

                    log.warn("LLM API 返回异常: status={}", response.getStatusCode());
                } catch (Exception retryEx) {
                    log.warn("LLM 调用失败 (第{}次): {}", retry + 1, retryEx.getMessage());
                    if (retry < maxRetries - 1) {
                        Thread.sleep(5000); // 重试前等5秒
                    } else {
                        throw retryEx;
                    }
                }
            }

            return "（LLM 摘要生成失败）";

        } catch (Exception e) {
            log.error("调用 LLM API 失败: {}", e.getMessage(), e);
            return "（LLM 摘要生成失败：" + e.getMessage() + "）";
        }
    }
}
