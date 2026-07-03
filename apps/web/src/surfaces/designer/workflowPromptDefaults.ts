import { AgentumApiError } from "../../services/apiClient";
import type { WorkflowNodeDraft } from "../../types/workflow-contract";

/** 新建单智能体/子智能体时预填的默认用户提示词，创建后用户可自行修改。 */
export const DEFAULT_SYSTEM_PROMPT = "请配置这个智能体的角色、任务边界和输出要求。";
export const DEFAULT_USER_PROMPT = "请基于已产生的可引用内容完成本步骤任务。";
export const DEFAULT_CLUSTER_USER_PROMPT = "请基于已产生的可引用内容完成本智能体任务。";
export const DEFAULT_INTENT_SYSTEM_PROMPT = [
  "你是多智能体节点的意图分派器。你的任务是把用户或上游变量表达的需求归类到设计时提供的候选意图。",
  "只能选择候选意图中的 intentCode，禁止返回 agentId、工具名、流程节点 ID 或任何未在候选列表中的代码。",
  "只输出一个 JSON 对象，不要输出 Markdown、解释文本或代码块。",
  'JSON 格式：{"intentCodes":["候选意图代码"],"reason":"一句中文原因","slots":{}}',
].join("\n");
export const DEFAULT_INTENT_USER_PROMPT = "请根据上游输入和候选意图，判断本次应该交给哪个子智能体处理。";

export type CustomPromptDraft = {
  systemPromptTemplateId?: string;
  userPromptTemplateId?: string;
  promptTemplateId?: string;
  systemPrompt?: string;
  userPrompt?: string;
};

function readTemplateId(value: unknown): string {
  const text = value == null ? "" : String(value).trim();
  return text === "" || text === "none" ? "none" : text;
}

function readPromptText(value: unknown): string {
  return value == null ? "" : String(value).trim();
}

function isTemplateAvailable(templateId: string, availableTemplateIds?: readonly string[]): boolean {
  return !availableTemplateIds || availableTemplateIds.includes(templateId);
}

/** 保存前补齐空白提示词，新建节点创建时已写入默认值。 */
export function normalizeAgentPromptConfig(
  config: Record<string, unknown>,
  defaultUserPrompt: string = DEFAULT_USER_PROMPT,
): Record<string, unknown> {
  const next = { ...config };
  if (readTemplateId(next.systemPromptTemplateId ?? next.promptTemplateId) === "none" && !readPromptText(next.systemPrompt)) {
    next.systemPrompt = DEFAULT_SYSTEM_PROMPT;
  }
  if (readTemplateId(next.userPromptTemplateId) === "none" && !readPromptText(next.userPrompt)) {
    next.userPrompt = defaultUserPrompt;
  }
  return next;
}

export function normalizeWorkflowNodeConfig(
  nodeType: string,
  config: Record<string, unknown>,
): Record<string, unknown> {
  if (nodeType === "agent") {
    return normalizeAgentPromptConfig(config);
  }
  if (nodeType === "parallel_group" && Array.isArray(config.clusterAgents)) {
    const clusterAgents = config.clusterAgents.map((agent) => (
      typeof agent === "object" && agent !== null
        ? normalizeAgentPromptConfig(agent as Record<string, unknown>, DEFAULT_CLUSTER_USER_PROMPT)
        : agent
    ));
    return {
      ...config,
      executionMode: normalizeClusterExecutionMode(config.executionMode),
      clusterOutputVariable: typeof config.clusterOutputVariable === "string" && config.clusterOutputVariable.trim() ? config.clusterOutputVariable : "cluster_result",
      mergeRule: typeof config.mergeRule === "string" && config.mergeRule.trim() ? config.mergeRule : buildDefaultClusterMergeRule(clusterAgents),
      intentSelectionMode: config.intentSelectionMode === "single" ? "single" : "multiple",
      intentInputTemplate: typeof config.intentInputTemplate === "string" ? config.intentInputTemplate : "",
      clusterAgents,
    };
  }
  if (nodeType === "delivery") {
    return normalizeDeliveryConfig(config);
  }
  return config;
}

function normalizeDeliveryConfig(config: Record<string, unknown>): Record<string, unknown> {
  const next = { ...config };
  const deliveryMode = typeof next.deliveryMode === "string" && next.deliveryMode.trim() ? next.deliveryMode.trim() : "direct";
  next.deliveryMode = deliveryMode;
  const deliveryConfigMode = next.deliveryConfigMode === "multiple" ? "multiple" : "single";
  next.deliveryConfigMode = deliveryConfigMode;
  if (deliveryConfigMode === "multiple") {
    next.deliveryExecutionPolicy = next.deliveryExecutionPolicy === "conditional" ? "conditional" : "all";
    next.deliveryItems = Array.isArray(next.deliveryItems)
      ? next.deliveryItems
        .filter((item) => typeof item === "object" && item !== null)
        .map((item, index) => normalizeDeliveryItem(item as Record<string, unknown>, index, deliveryMode))
      : [];
  } else {
    next.deliveryItems = [];
  }
  return next;
}

function normalizeDeliveryItem(item: Record<string, unknown>, index: number, deliveryMode: string): Record<string, unknown> {
  const config = typeof item.config === "object" && item.config !== null && !Array.isArray(item.config)
    ? item.config as Record<string, unknown>
    : {};
  return {
    ...item,
    id: typeof item.id === "string" && item.id.trim() ? item.id.trim() : `delivery_item_${index + 1}`,
    name: typeof item.name === "string" && item.name.trim() ? item.name.trim() : `交付项 ${index + 1}`,
    enabled: item.enabled !== false,
    triggerRule: normalizeDeliveryTriggerRule(item.triggerRule),
    config: {
      ...config,
      deliveryMode: typeof config.deliveryMode === "string" && config.deliveryMode.trim() ? config.deliveryMode.trim() : inferDeliveryMode(config, deliveryMode),
    },
  };
}

function inferDeliveryMode(config: Record<string, unknown>, parentDeliveryMode: string): string {
  const deliveryType = typeof config.deliveryType === "string" ? config.deliveryType.trim() : "";
  const capabilityId = typeof config.deliveryCapabilityId === "string" ? config.deliveryCapabilityId.trim() : "";
  if (deliveryType === "direct" || capabilityId === "none" || capabilityId === "custom") {
    return "direct";
  }
  if (capabilityId) {
    return "capability";
  }
  return parentDeliveryMode || "direct";
}

function normalizeDeliveryTriggerRule(value: unknown): Record<string, unknown> {
  const source = typeof value === "object" && value !== null && !Array.isArray(value) ? value as Record<string, unknown> : {};
  const type = typeof source.type === "string" && source.type.trim() ? source.type.trim() : "always";
  const normalizedType = ["cluster_agent_matched", "input_field_equals", "agent_output_exists"].includes(type) ? type : "always";
  return {
    type: normalizedType,
    clusterNodeId: typeof source.clusterNodeId === "string" ? source.clusterNodeId.trim() : "",
    agentId: typeof source.agentId === "string" ? source.agentId.trim() : "",
    inputNodeId: typeof source.inputNodeId === "string" ? source.inputNodeId.trim() : "",
    agentNodeId: typeof source.agentNodeId === "string" ? source.agentNodeId.trim() : "",
    variableName: typeof source.variableName === "string" ? source.variableName.trim() : "",
    expectedValue: typeof source.expectedValue === "string" ? source.expectedValue.trim() : "",
  };
}

function normalizeClusterExecutionMode(value: unknown): string {
  const mode = typeof value === "string" ? value.trim() : "";
  if (mode === "") return "collaborative";
  return mode;
}

function buildDefaultClusterMergeRule(agents: unknown[]): string {
  if (agents.length === 0) {
    return "## 智能体集群结论";
  }
  return [
    "## 智能体集群结论",
    ...agents.map((agent, index) => {
      const record = typeof agent === "object" && agent !== null ? agent as Record<string, unknown> : {};
      const name = typeof record.name === "string" && record.name.trim() ? record.name.trim() : `子智能体 ${index + 1}`;
      const output = typeof record.output === "string" && record.output.trim() ? record.output.trim() : `agent_${index + 1}_output`;
      return `\n### ${name}\n{{${output}}}`;
    }),
  ].join("\n");
}

/** 保存成功后以本次提交的配置覆盖服务端回读结果，避免提示词等字段在回写时丢失。 */
export function mergePersistedNodeConfigs(
  sentNodes: WorkflowNodeDraft[],
  persistedNodes: WorkflowNodeDraft[],
): WorkflowNodeDraft[] {
  const sentById = new Map(sentNodes.map((node) => [node.nodeId, node]));
  return persistedNodes.map((persisted) => {
    const sent = sentById.get(persisted.nodeId);
    if (!sent) {
      return persisted;
    }
    return {
      ...persisted,
      config: {
        ...(persisted.config ?? {}),
        ...(sent.config ?? {}),
      },
    };
  });
}

/** 自定义模式下系统/用户提示词至少配置一种（模板或正文）。 */
export function validateCustomPromptConfiguration(
  draft: CustomPromptDraft,
  availableTemplateIds?: readonly string[],
): string | null {
  const systemTemplateId = readTemplateId(draft.systemPromptTemplateId ?? draft.promptTemplateId);
  if (systemTemplateId === "none") {
    if (!readPromptText(draft.systemPrompt)) {
      return "系统提示词为自定义时必须填写正文，或选择系统提示词模板";
    }
  } else if (!isTemplateAvailable(systemTemplateId, availableTemplateIds)) {
    return "所选系统提示词模板当前不可用，请改选其他模板或切换为自定义并填写正文";
  }

  const userTemplateId = readTemplateId(draft.userPromptTemplateId);
  if (userTemplateId === "none") {
    if (!readPromptText(draft.userPrompt)) {
      return "用户提示词为自定义时必须填写正文，或选择用户提示词模板";
    }
  } else if (!isTemplateAvailable(userTemplateId, availableTemplateIds)) {
    return "所选用户提示词模板当前不可用，请改选其他模板或切换为自定义并填写正文";
  }

  return null;
}

type ValidationIssueDetail = {
  message?: string;
};

export function formatWorkflowSaveError(error: unknown): string {
  if (!(error instanceof AgentumApiError)) {
    return "保存工作流草稿失败";
  }
  const rawIssues = error.details?.issues;
  if (Array.isArray(rawIssues) && rawIssues.length > 0) {
    const messages = rawIssues
      .map((item) => (typeof item === "object" && item && "message" in item ? String((item as ValidationIssueDetail).message || "") : ""))
      .map((message) => message.trim())
      .filter(Boolean);
    if (messages.length === 1) {
      return messages[0];
    }
    if (messages.length > 1) {
      return `流程保存未通过：${messages.join("；")}`;
    }
  }
  return error.message || "保存工作流草稿失败";
}
