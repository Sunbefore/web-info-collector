package com.info.collector.dto;

import lombok.Data;

import java.util.List;

/**
 * 采集请求 DTO
 * 用于手动触发采集时传递参数
 */
@Data
public class CollectRequest {

    /**
     * 关键词（可选）
     * 如果填写，则只采集与关键词相关的内容
     * 如果为空，则采集当天所有最新资讯
     */
    private String keyword;

    /**
     * 指定采集的网站名称列表（可选）
     * 如果为空，则采集所有已启用的网站
     */
    private List<String> siteNames;

    /**
     * 日期范围-开始日期（可选，格式：yyyy-MM-dd）
     * 传入后按指定范围过滤，不传则默认查近一周
     * 只传 startDate 不传 endDate，则查 startDate 当天
     */
    private String startDate;

    /**
     * 日期范围-结束日期（可选，格式：yyyy-MM-dd）
     */
    private String endDate;

    /**
     * 是否推送通知（默认 true）
     */
    private boolean notify = true;
}
