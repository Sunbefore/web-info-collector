package com.info.collector.controller;

import com.info.collector.dto.ApiResponse;
import com.info.collector.dto.CollectRequest;
import com.info.collector.model.CollectResult;
import com.info.collector.model.SiteConfig;
import com.info.collector.service.CollectorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;

/**
 * 采集系统控制器
 * 提供手动触发采集、管理监控网站等 API
 */
@Slf4j
@RestController
@RequestMapping("/api/collector")
public class CollectorController {

    @Resource
    private CollectorService collectorService;

    /**
     * 手动触发采集任务
     *
     * POST /api/collector/collect
     * 请求体示例：
     * {
     *   "keyword": "反洗钱",          // 可选，关键词过滤
     *   "siteNames": ["中国人民银行"], // 可选，指定网站
     *   "notify": true                // 是否推送通知，默认 true
     * }
     *
     * 无参数时采集所有网站当天全部最新资讯
     */
    @PostMapping("/collect")
    public ApiResponse<List<CollectResult>> collect(@RequestBody(required = false) CollectRequest request) {
        if (request == null) {
            request = new CollectRequest();
        }

        log.info("收到手动采集请求，关键词: {}, 网站: {}", request.getKeyword(), request.getSiteNames());

        List<CollectResult> results = collectorService.collect(
                request.getKeyword(),
                request.getSiteNames(),
                request.isNotify(),
                request.getStartDate(),
                request.getEndDate()
        );

        long totalArticles = results.stream()
                .mapToLong(r -> r.getArticles().size())
                .sum();

        return ApiResponse.success(
                "采集完成，共采集 " + totalArticles + " 篇文章",
                results
        );
    }

    /**
     * 仅采集不推送（用于预览）
     *
     * POST /api/collector/preview
     */
    @PostMapping("/preview")
    public ApiResponse<List<CollectResult>> preview(@RequestBody(required = false) CollectRequest request) {
        if (request == null) {
            request = new CollectRequest();
        }
        request.setNotify(false);

        List<CollectResult> results = collectorService.collect(
                request.getKeyword(),
                request.getSiteNames(),
                false,
                request.getStartDate(),
                request.getEndDate()
        );

        return ApiResponse.success("预览完成（未发送通知）", results);
    }

    // ==================== 网站管理 ====================

    /**
     * 获取所有监控网站列表
     *
     * GET /api/collector/sites
     */
    @GetMapping("/sites")
    public ApiResponse<List<SiteConfig>> listSites() {
        return ApiResponse.success(collectorService.listAllSites());
    }

    /**
     * 添加监控网站
     *
     * POST /api/collector/sites
     * 请求体示例：
     * {
     *   "name": "中国证监会",
     *   "url": "http://www.csrc.gov.cn/csrc/c100028/common_list.shtml",
     *   "listSelector": ".list-group li",
     *   "titleSelector": "a",
     *   "linkSelector": "a",
     *   "dateSelector": "span.date",
     *   "dateFormat": "yyyy-MM-dd",
     *   "contentSelector": ".detail-content",
     *   "enabled": true
     * }
     */
    @PostMapping("/sites")
    public ApiResponse<String> addSite(@RequestBody SiteConfig siteConfig) {
        collectorService.addSite(siteConfig);
        return ApiResponse.success("网站添加成功: " + siteConfig.getName(), null);
    }

    /**
     * 删除监控网站（仅限动态添加的，配置文件中的需修改配置）
     *
     * DELETE /api/collector/sites/{name}
     */
    @DeleteMapping("/sites/{name}")
    public ApiResponse<String> removeSite(@PathVariable String name) {
        boolean removed = collectorService.removeSite(name);
        if (removed) {
            return ApiResponse.success("网站删除成功: " + name, null);
        } else {
            return ApiResponse.error("未找到动态添加的网站: " + name);
        }
    }
}
