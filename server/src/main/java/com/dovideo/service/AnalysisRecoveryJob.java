package com.dovideo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.dovideo.config.AppProperties;
import com.dovideo.entity.VideoTaskRecord;
import com.dovideo.enums.AnalysisStageEnum;
import com.dovideo.enums.TaskStatusEnum;
import com.dovideo.mapper.VideoTaskRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class AnalysisRecoveryJob {

    private final VideoTaskRecordMapper taskRecordMapper;
    private final AnalysisStateService stateService;
    private final TaskEventService taskEventService;
    private final AnalysisMetrics metrics;
    private final AppProperties appProperties;

    @Scheduled(fixedDelayString = "${app.analysis.recovery-interval-ms:300000}")
    public int recoverStaleTasks() {
        LocalDateTime threshold = LocalDateTime.now()
                .minusMinutes(appProperties.getAnalysis().getStaleAfterMinutes());
        List<VideoTaskRecord> candidates = taskRecordMapper.selectList(
                new LambdaQueryWrapper<VideoTaskRecord>()
                        .in(VideoTaskRecord::getStatus,
                                TaskStatusEnum.QUEUED.getCode(),
                                TaskStatusEnum.PROCESSING.getCode())
                        .lt(VideoTaskRecord::getUpdateTime, threshold));

        int recovered = 0;
        for (VideoTaskRecord task : candidates) {
            if (stateService.isActive(task.getId())) {
                continue;
            }
            String message = "分析任务长时间未推进且活动锁已失效，请重新提交";
            taskRecordMapper.update(null, new UpdateWrapper<VideoTaskRecord>()
                    .eq("id", task.getId())
                    .set("status", TaskStatusEnum.FAILED.getCode())
                    .set("error_msg", message)
                    .set("update_time", LocalDateTime.now()));
            stateService.markFailed(task.getId(), message);
            taskEventService.publishStage(
                    task.getId(), AnalysisStageEnum.FAILED, message, 0, message);
            metrics.staleRecovered();
            recovered++;
            log.warn("analysis_stale_task_recovered taskId={} videoMd5={}",
                    task.getId(), task.getVideoMd5());
        }
        return recovered;
    }
}
