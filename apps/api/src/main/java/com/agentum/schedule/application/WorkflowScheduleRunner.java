package com.agentum.schedule.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WorkflowScheduleRunner {

    private final WorkflowScheduleService scheduleService;

    public WorkflowScheduleRunner(WorkflowScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    // 小批量轮询到期定时任务；真实生产可替换为分布式调度锁，当前单体阶段先保持简单可审计。
    @Scheduled(fixedDelayString = "${agentum.workflow-schedule.poll-fixed-delay-ms:30000}")
    public void triggerDueSchedules() {
        scheduleService.triggerDueSchedules();
    }

    // 回填运行终态并推送消息，避免把通知逻辑塞进每个节点执行分支。
    @Scheduled(fixedDelayString = "${agentum.workflow-schedule.reconcile-fixed-delay-ms:30000}")
    public void reconcileRunningExecutions() {
        scheduleService.reconcileRunningExecutions();
    }
}
