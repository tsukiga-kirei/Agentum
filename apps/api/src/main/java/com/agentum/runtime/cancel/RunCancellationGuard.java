package com.agentum.runtime.cancel;

import java.util.UUID;

/**
 * 协作式取消守卫：Agent loop 与集群执行在模型轮次、工具调用间隙调用，
 * 用户主动中断或执行超过截止时间时抛出 ApiException 终止执行。
 */
public interface RunCancellationGuard {

    /** 请求中断指定任务，对所有执行进程生效（信号存放在 Redis，跨实例可见）。 */
    void requestCancel(UUID runId);

    /** 清除中断信号，重新执行前必须调用，否则新作业会被旧信号立即终止。 */
    void clearCancel(UUID runId);

    boolean isCancelled(UUID runId);

    /** 设置本次作业的执行截止时间（epoch 毫秒），超时后 assertExecutable 抛出超时异常。 */
    void markDeadline(UUID runId, long deadlineEpochMillis);

    /** 校验任务可继续执行：已取消抛 RUN_CANCELLED，超过截止时间抛 WORKBENCH_NODE_EXECUTION_TIMEOUT。 */
    void assertExecutable(UUID runId);
}
