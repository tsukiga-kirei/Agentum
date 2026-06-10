package com.agentum.runtime.reaper;

import com.agentum.runtime.cancel.RunCancellationGuard;
import com.agentum.runtime.execution.RuntimeExecutionProperties;
import com.agentum.runtime.lease.RunExecutionLeaseService;
import com.agentum.runtime.stream.RunProgressStreamWriter;
import com.agentum.workbench.application.WorkbenchRuntimeService;
import com.agentum.workflow.domain.WorkflowRunExecutionJobEntity;
import com.agentum.workflow.infrastructure.WorkflowRunExecutionJobRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 僵死/超时作业回收器。
 *
 * <p>失败可感知是异步执行的核心约束：执行进程崩溃（租约消失）、节点执行超过截止时间、
 * MQ 消息丢失（长期 queued）都必须把作业与节点标记为失败并通知前端，
 * 否则任务会假装「执行中」无限占用，用户也看不到「恢复进度」入口。</p>
 */
@Component
public class StaleExecutionReaper {

    private static final Logger log = LoggerFactory.getLogger(StaleExecutionReaper.class);

    private final WorkflowRunExecutionJobRepository jobRepository;
    private final WorkbenchRuntimeService workbenchRuntimeService;
    private final RunExecutionLeaseService leaseService;
    private final RunCancellationGuard cancellationGuard;
    private final RunProgressStreamWriter streamWriter;
    private final RuntimeExecutionProperties properties;
    private final Clock clock;

    public StaleExecutionReaper(
        WorkflowRunExecutionJobRepository jobRepository,
        WorkbenchRuntimeService workbenchRuntimeService,
        RunExecutionLeaseService leaseService,
        RunCancellationGuard cancellationGuard,
        RunProgressStreamWriter streamWriter,
        RuntimeExecutionProperties properties,
        Clock clock
    ) {
        this.jobRepository = jobRepository;
        this.workbenchRuntimeService = workbenchRuntimeService;
        this.leaseService = leaseService;
        this.cancellationGuard = cancellationGuard;
        this.streamWriter = streamWriter;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 30000)
    public void reap() {
        Instant now = clock.instant();
        List<WorkflowRunExecutionJobEntity> activeJobs = jobRepository.findByStatusInAndEnqueuedAtBefore(
            List.of(WorkflowRunExecutionJobEntity.STATUS_QUEUED, WorkflowRunExecutionJobEntity.STATUS_RUNNING),
            now.minusSeconds(5)
        );
        for (WorkflowRunExecutionJobEntity job : activeJobs) {
            try {
                reapJob(job, now);
            } catch (Exception exception) {
                log.error(
                    "回收僵死作业失败 jobId={} runId={} nodeRunId={}",
                    job.getId(),
                    job.getRunId(),
                    job.getNodeRunId(),
                    exception
                );
            }
        }
    }

    private void reapJob(WorkflowRunExecutionJobEntity job, Instant now) {
        if (WorkflowRunExecutionJobEntity.STATUS_RUNNING.equals(job.getStatus())) {
            // 超过执行截止时间：发取消信号让 Worker 尽快退出，并按失败收尾。
            if (job.getDeadlineAt() != null && now.isAfter(job.getDeadlineAt())) {
                cancellationGuard.requestCancel(job.getRunId());
                failJob(job, "WORKBENCH_NODE_EXECUTION_TIMEOUT", "节点执行超过最大时长限制，已自动中止，请重新执行", now);
                return;
            }
            // 租约消失且超过僵死阈值：执行进程已死亡（崩溃/重启），节点不能继续假装运行中。
            boolean staleByLease = !leaseService.hasActiveLease(job.getRunId())
                && job.getStartedAt() != null
                && job.getStartedAt().isBefore(now.minusSeconds(properties.getRedis().getStaleNodeThresholdSeconds()));
            if (staleByLease) {
                failJob(job, "WORKBENCH_NODE_EXECUTION_STALE", "执行进程已失联，节点执行被中止，请重新执行", now);
            }
            return;
        }
        long staleThresholdSeconds = properties.getRedis().getStaleNodeThresholdSeconds();
        boolean staleQueued = job.getEnqueuedAt().isBefore(now.minusSeconds(staleThresholdSeconds));
        // queued 且从未启动：租约占用或 MQ 重复投递留下的僵尸作业，不能等 30 分钟 nodeTimeout 才回收。
        boolean zombieQueued = job.getStartedAt() == null
            && job.getEnqueuedAt().isBefore(now.minusSeconds(30));
        if (staleQueued || zombieQueued) {
            if (leaseService.hasActiveLease(job.getRunId())) {
                leaseService.forceRelease(job.getRunId());
            }
            failJob(job, "WORKBENCH_NODE_EXECUTION_STALE", "执行命令长时间未被处理，已自动中止，请重新执行", now);
            return;
        }
        // queued 超过节点硬超时仍未被消费：MQ 消息丢失或 Worker 长期不可用。
        if (job.getEnqueuedAt().isBefore(now.minusSeconds(properties.getExecution().getNodeTimeoutSeconds()))) {
            failJob(job, "WORKBENCH_NODE_EXECUTION_STALE", "执行命令长时间未被处理，已自动中止，请重新执行", now);
        }
    }

    private void failJob(WorkflowRunExecutionJobEntity job, String errorCode, String errorMessage, Instant now) {
        job.markFailed(errorCode, errorMessage, now);
        jobRepository.save(job);
        boolean nodeFailed = workbenchRuntimeService.failNodeIfActive(job.getRunId(), job.getNodeRunId(), errorCode, errorMessage);
        if (nodeFailed) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("runId", job.getRunId().toString());
            payload.put("nodeRunId", job.getNodeRunId().toString());
            payload.put("timestamp", now.toString());
            payload.put("errorCode", errorCode);
            payload.put("errorMessage", errorMessage);
            streamWriter.append(job.getRunId(), "node_failed", payload);
            streamWriter.append(job.getRunId(), "message", "[DONE]");
        }
        log.warn(
            "僵死作业已回收 jobId={} runId={} nodeRunId={} errorCode={} attempt={}",
            job.getId(),
            job.getRunId(),
            job.getNodeRunId(),
            errorCode,
            job.getAttempt()
        );
    }
}
