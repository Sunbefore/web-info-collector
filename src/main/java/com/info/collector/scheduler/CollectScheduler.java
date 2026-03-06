package com.info.collector.scheduler;

import com.info.collector.service.CollectorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 定时采集任务调度器
 * 按照配置的 Cron 表达式定时执行资讯采集
 */
@Slf4j
@Component
public class CollectScheduler {

    @Resource
    private CollectorService collectorService;

    /**
     * 定时采集任务
     * 默认每天早上 8:30 执行（可在 application.yml 中修改）
     * 采集所有启用网站的当日最新资讯，不过滤关键词，并推送通知
     */
    @Scheduled(cron = "${collector.schedule.cron}")
    public void scheduledCollect() {
        log.info("====== 定时采集任务触发 ======");
        try {
            collectorService.collect(null, null, true, null, null);
        } catch (Exception e) {
            log.error("定时采集任务执行异常: {}", e.getMessage(), e);
        }
    }
}
