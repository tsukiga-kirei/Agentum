package com.agentum.workbench.application;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 记录用户主动中断的任务运行，供异步推进与 Agent loop 协作式取消。
 */
@Component
public class RunExecutionCancellationRegistry {

    private final Set<UUID> cancelledRuns = ConcurrentHashMap.newKeySet();

    public void requestCancel(UUID runId) {
        if (runId != null) {
            cancelledRuns.add(runId);
        }
    }

    public boolean isCancelled(UUID runId) {
        return runId != null && cancelledRuns.contains(runId);
    }

    public void clearCancel(UUID runId) {
        if (runId != null) {
            cancelledRuns.remove(runId);
        }
    }
}
