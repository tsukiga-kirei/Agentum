package com.agentum.workbench.application;

import com.agentum.workflow.domain.WorkflowNodeRunEntity;
import com.agentum.workflow.domain.WorkflowRunEntity;
import java.util.Map;
import java.util.UUID;

/**
 * 工作流运行节点执行器。
 *
 * <p>业务工作台状态机只负责“按发布版本推进节点、暂停与恢复、写入状态”，真实节点能力
 * 由执行器完成。这样后续替换为异步 Runner 或 Worker 时，不需要改待办与任务记录口径。</p>
 */
public interface WorkflowRuntimeExecutor {

    ExecutionResult execute(ExecutionRequest request);

    record ExecutionRequest(
        WorkflowRunEntity run,
        WorkflowNodeRunEntity nodeRun,
        Map<String, Object> variables,
        UUID operatorUserId
    ) {
    }

    record ExecutionResult(Map<String, Object> outputs) {
        public ExecutionResult {
            outputs = outputs == null ? Map.of() : Map.copyOf(outputs);
        }
    }
}
