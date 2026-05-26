package com.info.collector.scheduler;

import com.info.collector.config.CollectorProperties;
import com.info.collector.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CollectSchedulerTest {

    @Test
    void scheduledCollectUsesConfiguredKeywordForKeywordGrouping() {
        CollectorService collectorService = mock(CollectorService.class);
        CollectorProperties properties = new CollectorProperties();
        properties.getSchedule().setIntervalDays(1);
        properties.getSchedule().setKeyword("\u53cd\u6d17\u94b1,\u6a21\u578b");

        CollectScheduler scheduler = new CollectScheduler();
        ReflectionTestUtils.setField(scheduler, "collectorService", collectorService);
        ReflectionTestUtils.setField(scheduler, "properties", properties);

        scheduler.scheduledCollect();

        ArgumentCaptor<String> keywordCaptor = ArgumentCaptor.forClass(String.class);
        verify(collectorService).collect(keywordCaptor.capture(), isNull(), anyBoolean(), anyString(), anyString());

        assertThat(keywordCaptor.getValue()).isEqualTo("\u53cd\u6d17\u94b1,\u6a21\u578b");
    }
}
