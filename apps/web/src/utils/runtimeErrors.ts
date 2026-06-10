const RUNTIME_ERROR_HINTS: Record<string, string> = {
  MODEL_MAX_TOKENS_REQUIRED:
    "未配置最大输出 Token。请前往系统管理 > 模型供应商填写「最大输出 Token」（建议 8192 或以上），或在流程设计器的智能体节点中单独指定。",
  AGENT_LOOP_FAILED: "智能体执行失败，请查看下方错误说明后重试或回退上一步。",
  MODEL_CALL_FAILED: "模型调用失败，请检查供应商连通性、API Key 与模型名称。",
  MODEL_RESPONSE_INVALID: "模型返回无法解析，请稍后重试或更换模型。",
  CLUSTER_AGENT_FAILED: "子智能体执行失败，请展开对应子智能体查看原因。",
  WORKBENCH_NODE_EXECUTION_FAILED: "节点执行异常，请重试或联系管理员查看运行日志。",
  WORKBENCH_NODE_EXECUTION_TIMEOUT: "节点执行超过最大时长限制，已自动中止。已成功的子智能体结果已保留，可恢复进度继续执行。",
  WORKBENCH_NODE_EXECUTION_STALE: "后台执行进程已失联（可能服务重启或异常退出）。已成功的部分已保留，可恢复进度继续执行。",
  WORKBENCH_ADVANCE_ALREADY_IN_FLIGHT: "当前任务已有执行在进行中，请等待其完成或刷新页面查看最新进度。",
};

/** 将后端 errorCode / errorMessage 转为用户可读文案。 */
export function formatRuntimeErrorMessage(errorCode?: string | null, errorMessage?: string | null): string {
  const code = errorCode?.trim() || "";
  const message = errorMessage?.trim() || "";
  if (code && RUNTIME_ERROR_HINTS[code]) {
    return message && !message.includes(RUNTIME_ERROR_HINTS[code].slice(0, 8))
      ? `${RUNTIME_ERROR_HINTS[code]}（${message}）`
      : RUNTIME_ERROR_HINTS[code];
  }
  if (message) {
    return message;
  }
  if (code) {
    return `执行失败（${code}）`;
  }
  return "节点执行失败，请重试或回退上一步。";
}

/** 判断持久化的子智能体快照是否表示执行失败（不扫描 final_answer 正文关键词）。 */
export function isPersistedClusterAgentFailed(agent: Record<string, unknown>): boolean {
  const status = agent.status ?? agent.state;
  if (status === "failed") {
    return true;
  }
  if (status === "completed" || status === "success") {
    return false;
  }

  const errorCode = agent.errorCode ?? agent.error_code;
  if (typeof errorCode === "string" && errorCode.trim()) {
    return true;
  }

  const finalAnswer = agent.final_answer ?? agent.finalAnswer;
  if (typeof finalAnswer === "string" && finalAnswer.trim().length > 0) {
    return false;
  }

  const summary = typeof agent.summary === "string" ? agent.summary.trim() : "";
  if (!summary) {
    return false;
  }

  // 失败快照通常只有短错误摘要，不含完整 Markdown 输出。
  if (summary.length > 240) {
    return false;
  }

  return (
    summary.startsWith("执行失败")
    || summary.includes("CLUSTER_AGENT_FAILED")
    || summary.includes("MODEL_MAX_TOKENS")
    || summary.includes("MODEL_CALL_FAILED")
    || summary.includes("AGENT_LOOP_FAILED")
    || summary.includes("未配置最大输出 Token")
  );
}
