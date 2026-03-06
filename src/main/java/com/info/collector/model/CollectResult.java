package com.info.collector.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 采集结果模型
 * 包含某个网站的采集结果和 LLM 汇总摘要
 */
@Data
public class CollectResult {

    /** 来源网站名称 */
    private String siteName;

    /** 来源网站 URL */
    private String siteUrl;

    /** 采集到的文章列表 */
    private List<Article> articles = new ArrayList<>();

    /** LLM 对该网站当日资讯的整体摘要 */
    private String overallSummary;

    /** 采集时间 */
    private String collectTime;

    /** 使用的关键词过滤（为空表示采集全部） */
    private String keyword;

    /** 是否采集成功 */
    private boolean success = true;

    /** 错误信息（采集失败时） */
    private String errorMessage;
}
