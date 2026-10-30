package com.agentum.runtime;

import com.agentum.shared.logging.LogContext;
import com.agentum.workbench.application.WorkbenchRuntimeService;
import com.agentum.workflow.infrastructure.WorkflowRunRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 自动运行在节点完成与下一步入队之间重启时，从数据库事实状态恢复推进。 */
@Component
public class AutomaticRunReconciler {
    private static final Logger log = LoggerFactory.getLogger(AutomaticRunReconciler.class);
    private final WorkflowRunRepository runs;
    private final WorkbenchRuntimeService runtime;

    public AutomaticRunReconciler(WorkflowRunRepository runs, WorkbenchRuntimeService runtime) {
        this.runs = runs;
        this.runtime = runtime;
    }

    @Scheduled(fixedDelay = 5000)
    public void reconcile() {
        for (var run : runs.findTop100ByAutoExecutionTrueAndManualTakeoverFalseAndStateInOrderByUpdatedAtAsc(List.of("running", "paused"))) {
            try (LogContext.Scope ignored = LogContext.openTenantOperation(run.getTenantId(), run.getCreatedBy(), run.getId(), null, null, null)) {
                runtime.continueAutomaticRunAfterJob(run.getId(), run.getCreatedBy());
            } catch (Exception error) {
                log.error("恢复自动运行推进失败 tenantId={} userId={} runId={}", run.getTenantId(), run.getCreatedBy(), run.getId(), error);
            }
        }
    }
}
