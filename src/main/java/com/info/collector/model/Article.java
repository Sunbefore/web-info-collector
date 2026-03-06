package com.info.collector.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 文章模型
 * 表示从网站抓取到的一篇资讯文章
 */
@Data
public class Article {

    /** 文章标题 */
    private String title;

    /** 文章链接 */
    private String url;

    /** 发布日期（字符串格式） */
    private String publishDate;

    /** 文章正文内容（纯文本） */
    private String content;

    /** 文章来源网站名称 */
    private String sourceSite;

    /** 文章中包含的图片链接 */
    private List<String> imageUrls = new ArrayList<>();

    /** 文章中包含的附件/文档链接 */
    private List<String> documentUrls = new ArrayList<>();

    /** 匹配到的关键词列表 */
    private List<String> matchedKeywords = new ArrayList<>();

    /** LLM 生成的摘要 */
    private String summary;
}
