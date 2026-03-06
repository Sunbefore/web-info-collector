package com.info.collector;

import cn.hutool.core.date.DateUtil;
import com.info.collector.service.CollectorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.Date;

/**
 * 网站资讯智能采集与推送系统
 *
 * 启动模式：
 * 1. 常驻服务模式（默认）：启动Web服务 + 定时任务
 * 2. 单次执行模式：--run-once 启动后执行一次采集并退出
 *    参数：--keyword=反洗钱,模型  --days=1（往前查几天，默认1天）
 */
@Slf4j
@EnableScheduling
@SpringBootApplication
public class WebInfoCollectorApplication implements CommandLineRunner {

    @Resource
    private CollectorService collectorService;

    @Resource
    private ConfigurableApplicationContext applicationContext;

    public static void main(String[] args) {
        SpringApplication.run(WebInfoCollectorApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        boolean runOnce = Arrays.asList(args).contains("--run-once");
        if (!runOnce) {
            return;
        }

        log.info("========== 单次执行模式 ==========");

        // 解析参数
        String keyword = "反洗钱,模型";
        int days = 1;
        for (String arg : args) {
            if (arg.startsWith("--keyword=")) {
                keyword = arg.substring("--keyword=".length());
            } else if (arg.startsWith("--days=")) {
                days = Integer.parseInt(arg.substring("--days=".length()));
            }
        }

        // 计算日期范围：前N天到昨天
        Date endDate = DateUtil.offsetDay(new Date(), -1);
        Date startDate = DateUtil.offsetDay(new Date(), -days);
        String startStr = DateUtil.format(startDate, "yyyy-MM-dd");
        String endStr = DateUtil.format(endDate, "yyyy-MM-dd");

        log.info("关键词: {}，日期范围: {} ~ {}", keyword, startStr, endStr);

        try {
            collectorService.collect(keyword, null, true, startStr, endStr);
            log.info("========== 单次执行完成 ==========");
        } catch (Exception e) {
            log.error("单次执行失败: {}", e.getMessage(), e);
        }

        // 退出应用
        SpringApplication.exit(applicationContext, () -> 0);
    }
}
