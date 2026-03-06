package com.info.collector.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSONObject;
import com.info.collector.config.CollectorProperties;
import com.info.collector.model.Article;
import com.info.collector.model.CollectResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.Resource;
import javax.mail.internet.MimeMessage;
import java.util.Date;
import java.util.List;

/**
 * 通知推送服务
 * 支持邮件推送和企业微信机器人推送
 */
@Slf4j
@Service
public class NotifyService {

    @Resource
    private JavaMailSenderImpl mailSender;

    @Resource
    private RestTemplate restTemplate;

    @Resource
    private CollectorProperties properties;

    /**
     * 发送采集结果通知
     * 同时尝试邮件和微信推送（根据配置）
     *
     * @param results 采集结果列表
     * @param keyword 关键词（可为空）
     */
    public void sendNotification(List<CollectResult> results, String keyword, Date startDate, Date endDate) {
        String dateRangeStr = buildDateRangeStr(startDate, endDate);
        String reportTitle = buildReportTitle(startDate, endDate);

        String htmlContent = buildHtmlReport(results, keyword, dateRangeStr, reportTitle);
        String markdownContent = buildMarkdownReport(results, keyword, dateRangeStr, reportTitle);

        // 邮件推送
        if (StrUtil.isNotBlank(properties.getNotify().getMailTo())) {
            sendEmail(htmlContent, keyword, dateRangeStr, reportTitle);
        }

        // 企业微信推送
        if (StrUtil.isNotBlank(properties.getNotify().getWechatWebhook())) {
            sendWechatMessage(markdownContent);
        }

        // PushPlus 个人微信推送
        if (StrUtil.isNotBlank(properties.getNotify().getPushplusToken())) {
            sendPushPlus(htmlContent, reportTitle, dateRangeStr, keyword);
        }
    }

    /**
     * 根据日期范围生成报告标题（日报/周报/资讯摘要）
     */
    private String buildReportTitle(Date startDate, Date endDate) {
        long days = (endDate.getTime() - startDate.getTime()) / (1000 * 60 * 60 * 24);
        if (days <= 1) {
            return "每日资讯摘要";
        } else {
            return "资讯摘要";
        }
    }

    /**
     * 生成日期范围显示文本
     */
    private String buildDateRangeStr(Date startDate, Date endDate) {
        String start = DateUtil.format(startDate, "yyyy年MM月dd日");
        String end = DateUtil.format(endDate, "yyyy年MM月dd日");
        if (start.equals(end)) {
            return start;
        }
        return start + " ~ " + end;
    }

    /**
     * 发送 HTML 格式邮件
     *
     * @param htmlContent 邮件 HTML 内容
     * @param keyword     关键词
     */
    private void sendEmail(String htmlContent, String keyword, String dateRangeStr, String reportTitle) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(mailSender.getUsername());
            helper.setTo(properties.getNotify().getMailTo().split(","));

            // 邮件主题
            String subject = "【" + reportTitle + "】" + dateRangeStr;
            if (StrUtil.isNotBlank(keyword)) {
                subject += " - 关键词：" + keyword;
            }
            helper.setSubject(subject);
            helper.setText(htmlContent, true);

            mailSender.send(message);
            log.info("邮件发送成功，收件人: {}", properties.getNotify().getMailTo());

        } catch (Exception e) {
            log.error("邮件发送失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 发送企业微信机器人消息
     * 使用 Markdown 格式
     *
     * @param markdownContent Markdown 内容
     */
    private void sendWechatMessage(String markdownContent) {
        try {
            // 企业微信 Webhook 消息体限制 4096 字节
            if (markdownContent.length() > 3500) {
                markdownContent = markdownContent.substring(0, 3500) + "\n\n> ...内容过长已截断，详情请查看邮件";
            }

            JSONObject body = new JSONObject();
            body.put("msgtype", "markdown");

            JSONObject markdown = new JSONObject();
            markdown.put("content", markdownContent);
            body.put("markdown", markdown);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(body.toJSONString(), headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    properties.getNotify().getWechatWebhook(),
                    HttpMethod.POST, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                log.info("企业微信消息发送成功");
            } else {
                log.warn("企业微信消息发送异常: {}", response.getBody());
            }

        } catch (Exception e) {
            log.error("企业微信消息发送失败: {}", e.getMessage(), e);
        }
    }

    /**
     * PushPlus 个人微信推送（支持HTML格式）
     */
    private void sendPushPlus(String htmlContent, String reportTitle, String dateRangeStr, String keyword) {
        try {
            String title = reportTitle + " " + dateRangeStr;
            if (StrUtil.isNotBlank(keyword)) {
                title += " - " + keyword;
            }

            JSONObject body = new JSONObject();
            body.put("token", properties.getNotify().getPushplusToken());
            body.put("title", title);
            body.put("content", htmlContent);
            body.put("template", "html");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(body.toJSONString(), headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    "https://www.pushplus.plus/send",
                    HttpMethod.POST, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                JSONObject result = com.alibaba.fastjson2.JSON.parseObject(response.getBody());
                if (result != null && result.getIntValue("code") == 200) {
                    log.info("PushPlus微信推送成功");
                } else {
                    log.warn("PushPlus推送异常: {}", response.getBody());
                }
            } else {
                log.warn("PushPlus推送HTTP异常: {}", response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("PushPlus微信推送失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 构建 HTML 格式的邮件报告
     * 美观的表格+卡片布局
     */
    private String buildHtmlReport(List<CollectResult> results, String keyword, String dateRangeStr, String reportTitle) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'>");
        html.append("<style>");
        html.append("body { font-family: 'Microsoft YaHei', Arial, sans-serif; background: #f5f7fa; padding: 20px; color: #333; }");
        html.append(".container { max-width: 800px; margin: 0 auto; background: #fff; border-radius: 12px; box-shadow: 0 2px 12px rgba(0,0,0,0.1); overflow: hidden; }");
        html.append(".header { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: #fff; padding: 30px; text-align: center; }");
        html.append(".header h1 { margin: 0; font-size: 24px; }");
        html.append(".header p { margin: 8px 0 0; opacity: 0.9; font-size: 14px; }");
        html.append(".keyword-tag { display: inline-block; background: rgba(255,255,255,0.2); padding: 4px 12px; border-radius: 20px; margin-top: 10px; font-size: 13px; }");
        html.append(".content { padding: 25px; }");
        html.append(".site-section { margin-bottom: 30px; }");
        html.append(".site-title { font-size: 18px; color: #667eea; border-left: 4px solid #667eea; padding-left: 12px; margin-bottom: 15px; }");
        html.append(".summary-box { background: #f8f9ff; border-radius: 8px; padding: 15px; margin-bottom: 20px; border: 1px solid #e8ecf1; line-height: 1.8; }");
        html.append(".article-card { border: 1px solid #eee; border-radius: 8px; padding: 15px; margin-bottom: 12px; transition: box-shadow 0.3s; }");
        html.append(".article-card:hover { box-shadow: 0 2px 8px rgba(0,0,0,0.1); }");
        html.append(".article-title { font-size: 15px; font-weight: bold; color: #333; }");
        html.append(".article-title a { color: #667eea; text-decoration: none; }");
        html.append(".article-meta { font-size: 12px; color: #999; margin-top: 5px; }");
        html.append(".article-summary { font-size: 13px; color: #666; margin-top: 8px; line-height: 1.6; }");
        html.append(".highlight { background: #fff3cd; padding: 2px 4px; border-radius: 3px; font-weight: bold; }");
        html.append(".doc-link { display: inline-block; background: #e8f4fd; color: #1890ff; padding: 3px 8px; border-radius: 4px; font-size: 12px; margin: 2px; text-decoration: none; }");
        html.append(".footer { text-align: center; padding: 20px; color: #999; font-size: 12px; border-top: 1px solid #eee; }");
        html.append(".error-box { background: #fff2f0; border: 1px solid #ffccc7; border-radius: 8px; padding: 12px; color: #cf1322; }");
        html.append(".empty-box { text-align: center; padding: 40px; color: #999; }");
        html.append("</style></head><body>");

        // 头部
        html.append("<div class='container'>");
        html.append("<div class='header'>");
        html.append("<h1>📰 ").append(reportTitle).append("</h1>");
        html.append("<p>").append(dateRangeStr).append("</p>");
        if (StrUtil.isNotBlank(keyword)) {
            html.append("<span class='keyword-tag'>🔍 关键词：").append(keyword).append("</span>");
        }
        html.append("</div>");

        html.append("<div class='content'>");

        // 逐个网站输出结果
        boolean hasContent = false;
        for (CollectResult result : results) {
            html.append("<div class='site-section'>");
            html.append("<h2 class='site-title'>").append(result.getSiteName()).append("</h2>");

            if (!result.isSuccess()) {
                html.append("<div class='error-box'>采集失败：").append(result.getErrorMessage()).append("</div>");
                html.append("</div>");
                continue;
            }

            if (result.getArticles().isEmpty()) {
                html.append("<div class='empty-box'>当日暂无相关资讯</div>");
                html.append("</div>");
                continue;
            }

            hasContent = true;

            // LLM 整体摘要
            if (StrUtil.isNotBlank(result.getOverallSummary())) {
                html.append("<div class='summary-box'>");
                // 将 Markdown 格式的摘要简单转为 HTML
                String summaryHtml = markdownToSimpleHtml(result.getOverallSummary());
                html.append(summaryHtml);
                html.append("</div>");
            }

            // 文章列表
            for (Article article : result.getArticles()) {
                html.append("<div class='article-card'>");
                html.append("<div class='article-title'>");
                html.append("<a href='").append(article.getUrl()).append("' target='_blank'>");
                html.append(article.getTitle()).append("</a></div>");
                html.append("<div class='article-meta'>📅 ").append(article.getPublishDate());
                html.append(" | 🏛️ ").append(article.getSourceSite()).append("</div>");

                if (StrUtil.isNotBlank(article.getSummary())) {
                    html.append("<div class='article-summary'>").append(markdownToSimpleHtml(article.getSummary())).append("</div>");
                }

                // 附件链接
                if (!article.getDocumentUrls().isEmpty()) {
                    html.append("<div style='margin-top: 8px;'>");
                    for (String docUrl : article.getDocumentUrls()) {
                        String fileName = docUrl.substring(docUrl.lastIndexOf("/") + 1);
                        html.append("<a class='doc-link' href='").append(docUrl)
                                .append("' target='_blank'>📄 ").append(fileName).append("</a>");
                    }
                    html.append("</div>");
                }

                html.append("</div>");
            }

            html.append("</div>");
        }

        if (!hasContent) {
            html.append("<div class='empty-box'>📭 今日各站点均暂无最新资讯</div>");
        }

        html.append("</div>");

        // 页脚
        html.append("<div class='footer'>");
        html.append("此邮件由「网站资讯智能采集系统」自动生成并发送<br>");
        html.append("采集时间：").append(DateUtil.format(new Date(), "yyyy-MM-dd HH:mm:ss"));
        html.append("</div>");

        html.append("</div></body></html>");
        return html.toString();
    }

    /**
     * 构建 Markdown 格式的报告（用于企业微信推送）
     */
    private String buildMarkdownReport(List<CollectResult> results, String keyword, String dateRangeStr, String reportTitle) {
        StringBuilder md = new StringBuilder();
        md.append("# 📰 ").append(reportTitle).append("\n");
        md.append("> 日期：").append(dateRangeStr).append("\n\n");

        if (StrUtil.isNotBlank(keyword)) {
            md.append("> 🔍 关键词：**").append(keyword).append("**\n\n");
        }

        for (CollectResult result : results) {
            md.append("## ").append(result.getSiteName()).append("\n\n");

            if (!result.isSuccess()) {
                md.append("> ⚠️ 采集失败：").append(result.getErrorMessage()).append("\n\n");
                continue;
            }

            if (result.getArticles().isEmpty()) {
                md.append("> 当日暂无相关资讯\n\n");
                continue;
            }

            // LLM 摘要
            if (StrUtil.isNotBlank(result.getOverallSummary())) {
                md.append(result.getOverallSummary()).append("\n\n");
            }

            // 文章列表
            for (Article article : result.getArticles()) {
                md.append("- **").append(article.getTitle()).append("**");
                md.append(" (").append(article.getPublishDate()).append(")\n");
                md.append("  [查看原文](").append(article.getUrl()).append(")\n");
            }
            md.append("\n");
        }

        md.append("---\n");
        md.append("*由「资讯采集系统」自动生成 ").append(DateUtil.format(new Date(), "HH:mm:ss")).append("*\n");

        return md.toString();
    }

    /**
     * 简单的 Markdown 到 HTML 转换
     * 处理标题、加粗、列表等基本格式
     */
    private String markdownToSimpleHtml(String markdown) {
        if (StrUtil.isBlank(markdown)) {
            return "";
        }
        String html = markdown;
        // 标题
        html = html.replaceAll("(?m)^### (.+)$", "<h4>$1</h4>");
        html = html.replaceAll("(?m)^## (.+)$", "<h3 style='color:#667eea;margin:15px 0 8px;'>$1</h3>");
        html = html.replaceAll("(?m)^# (.+)$", "<h2 style='color:#667eea;'>$1</h2>");
        // 链接 [text](url) → <a href="url">text</a>
        html = html.replaceAll("\\[([^\\]]+)\\]\\(([^)]+)\\)", "<a href='$2' target='_blank' style='color:#667eea;'>$1</a>");
        // 加粗
        html = html.replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>");
        // 列表项
        html = html.replaceAll("(?m)^- (.+)$", "<li style='margin:4px 0;'>$1</li>");
        html = html.replaceAll("(?m)^\\d+\\. (.+)$", "<li style='margin:4px 0;'>$1</li>");
        // 换行
        html = html.replace("\n", "<br>");
        return html;
    }
}
