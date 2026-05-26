package com.info.collector.scheduler;

import cn.hutool.core.date.DateUtil;
import com.info.collector.config.CollectorProperties;
import com.info.collector.service.CollectorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Date;

/**
 * 定时采集任务调度器
 * 默认每3天执行一次，采集最近3天的资讯
 * --run-once 模式下通过 collector.schedule.enabled=false 禁用
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "collector.schedule.enabled", havingValue = "true", matchIfMissing = true)
public class CollectScheduler {

    @Resource
    private CollectorService collectorService;

    @Resource
    private CollectorProperties properties;

    @Scheduled(cron = "${collector.schedule.cron:0 0 6 * * ?}")
    public void scheduledCollect() {
        int intervalDays = properties.getSchedule().getIntervalDays();
        log.info("====== 定时采集任务触发（每{}天执行一次） ======", intervalDays);
        try {
            Date endDate = DateUtil.offsetDay(new Date(), -1);
            Date startDate = DateUtil.offsetDay(new Date(), -intervalDays);
            String startStr = DateUtil.format(startDate, "yyyy-MM-dd");
            String endStr = DateUtil.format(endDate, "yyyy-MM-dd");
            collectorService.collect(properties.getSchedule().getKeyword(), null, true, startStr, endStr);
        } catch (Exception e) {
            log.error("定时采集任务执行异常: {}", e.getMessage(), e);
        }
    }
}
