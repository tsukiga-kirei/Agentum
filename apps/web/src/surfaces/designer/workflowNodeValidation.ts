import type { WorkflowNodeType } from "../../types/workflow-contract";
import { normalizeInputFieldOptions } from "../../utils/workflowInputField";
import { collectDeliveryTriggerVariableNames } from "./deliveryTriggerContext";
import { validateCustomPromptConfiguration } from "./workflowPromptDefaults";

/** 运行时可注入的模板变量，不计入上游输出校验。 */
export const WORKFLOW_SYSTEM_TEMPLATE_VARIABLES = new Set([
  "runId",
  "runNumber",
  "date",
  "dateCompact",
  "current_date",
  "current_date_cn",
  "current_weekday",
  "current_year",
  "current_month",
  "current_day",
  "year",
  "month",
  "day",
]);

const VARIABLE_NAME_PATTERN = /^[a-z][a-z0-9_]*$/;

export type WorkflowNodeValidationIssue = {
  code: string;
  message: string;
};

export type ValidatableWorkflowNode = {
  id: string;
  data: {
    label: string;
    nodeType: WorkflowNodeType;
    outputVariables: string[];
    rawConfig?: Record<string, unknown>;
  };
};

type VisibleBrickType = "input" | "agent" | "cluster" | "delivery";
type ClusterExecutionMode = "collaborative" | "relay" | "intent";

function readString(value: unknown, fallback = ""): string {
  if (typeof value === "string") {
    const text = value.trim();
    return text || fallback;
  }
  if (value == null) {
    return fallback;
  }
  return String(value).trim() || fallback;
}

function readStringArray(value: unknown): string[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value
    .map((item) => readString(item))
    .filter(Boolean);
}

function readMapList(value: unknown): Array<Record<string, unknown>> {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.filter((item): item is Record<string, unknown> => typeof item === "object" && item !== null);
}

function readMap(value: unknown): Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value) ? value as Record<string, unknown> : {};
}

function inferBrickType(node: ValidatableWorkflowNode): VisibleBrickType {
  const configured = readString(node.data.rawConfig?.brickType);
  if (configured === "input" || configured === "agent" || configured === "cluster" || configured === "delivery") {
    return configured;
  }
  if (node.data.nodeType === "user_input") {
    return "input";
  }
  if (node.data.nodeType === "agent") {
    return "agent";
  }
  if (node.data.nodeType === "parallel_group" || node.data.nodeType === "merge") {
    return "cluster";
  }
  return "delivery";
}

function issue(code: string, message: string): WorkflowNodeValidationIssue {
  return { code, message };
}

function isSentinelId(value: string): boolean {
  return value === "" || value === "none" || value === "custom";
}

function normalizeClusterExecutionMode(value: unknown): ClusterExecutionMode {
  const mode = readString(value, "collaborative");
  return mode === "relay" || mode === "intent" ? mode : "collaborative";
}

export function extractTemplateVariableNames(text: string): string[] {
  const names = new Set<string>();
  for (const match of text.matchAll(/\{\{\s*([a-zA-Z0-9_]+)\s*\}\}/g)) {
    names.add(match[1]);
  }
  return [...names];
}

type TemplateTextField = {
  key: string;
  label: string;
  text: string;
};

function isDirectDeliveryConfig(config: Record<string, unknown>): boolean {
  return inferDeliveryMode(config, "direct") === "direct" || readString(config.deliveryType) === "direct";
}

function inferDeliveryMode(config: Record<string, unknown>, parentDeliveryMode: string): string {
  const deliveryMode = readString(config.deliveryMode);
  if (deliveryMode) {
    return deliveryMode;
  }
  const deliveryType = readString(config.deliveryType);
  const capabilityId = readString(config.deliveryCapabilityId);
  if (deliveryType === "direct" || capabilityId === "none" || capabilityId === "custom") {
    return "direct";
  }
  if (capabilityId) {
    return "capability";
  }
  return parentDeliveryMode || "direct";
}

function isWordCapabilityDeliveryConfig(config: Record<string, unknown>): boolean {
  if (isDirectDeliveryConfig(config)) {
    return false;
  }
  const deliveryType = readString(config.deliveryType);
  const documentKind = readString(config.documentKind);
  return deliveryType === "word_document" || documentKind === "word";
}

function isExcelCapabilityDeliveryConfig(config: Record<string, unknown>): boolean {
  if (isDirectDeliveryConfig(config)) {
    return false;
  }
  const deliveryType = readString(config.deliveryType);
  const documentKind = readString(config.documentKind);
  return deliveryType === "excel_workbook" || documentKind === "excel";
}

/** 只扫描会参与运行或交付正文的模板字段，避免误扫 previewMarkdown 等设计态残留。 */
export function collectRuntimeTemplateTextFields(
  config: Record<string, unknown> | undefined,
  brickType: VisibleBrickType,
): TemplateTextField[] {
  const source = config ?? {};
  const fields: TemplateTextField[] = [];
  const push = (key: string, label: string, value: unknown) => {
    const text = readString(value);
    if (text) {
      fields.push({ key, label, text });
    }
  };

  if (brickType === "delivery") {
    // 交付节点会保留历史模式下的字段；校验时必须按当前生效模式扫描，避免 Word 模式下仍误读 deliveryContent。
    if (readString(source.deliveryConfigMode, "single") === "multiple") {
      readMapList(source.deliveryItems).forEach((item, index) => {
        const itemName = readString(item.name, `交付项 ${index + 1}`);
        const itemConfig = readMap(item.config);
        if (isDirectDeliveryConfig(itemConfig)) {
          push(`deliveryItems.${index}.config.deliveryContent`, `${itemName}交付内容`, itemConfig.deliveryContent);
          push(`deliveryItems.${index}.config.deliveryTarget`, `${itemName}交付内容`, itemConfig.deliveryTarget);
          push(`deliveryItems.${index}.config.body`, `${itemName}交付内容`, itemConfig.body);
          return;
        }
        if (isWordCapabilityDeliveryConfig(itemConfig)) {
          push(`deliveryItems.${index}.config.markdownContent`, `${itemName}正文模板`, itemConfig.markdownContent);
          push(`deliveryItems.${index}.config.fileNameTemplate`, `${itemName}文件名模板`, itemConfig.fileNameTemplate);
          return;
        }
        if (isExcelCapabilityDeliveryConfig(itemConfig)) {
          push(`deliveryItems.${index}.config.fileNameTemplate`, `${itemName}文件名模板`, itemConfig.fileNameTemplate);
          readMapList(itemConfig.excelSheets).forEach((sheet, sheetIndex) => {
            const sheetName = readString(sheet.name, `Sheet ${sheetIndex + 1}`);
            push(`deliveryItems.${index}.config.excelSheets.${sheetIndex}.bodyTemplate`, `${itemName}${sheetName}正文模板`, sheet.bodyTemplate);
          });
          return;
        }
        push(`deliveryItems.${index}.config.deliveryContent`, `${itemName}交付内容`, itemConfig.deliveryContent);
        push(`deliveryItems.${index}.config.deliveryTarget`, `${itemName}交付内容`, itemConfig.deliveryTarget);
        push(`deliveryItems.${index}.config.body`, `${itemName}交付内容`, itemConfig.body);
      });
      return fields;
    }
    if (isDirectDeliveryConfig(source)) {
      push("deliveryContent", "直接交付内容", source.deliveryContent);
      push("deliveryTarget", "直接交付内容", source.deliveryTarget);
      push("body", "直接交付内容", source.body);
      return fields;
    }
    if (isWordCapabilityDeliveryConfig(source)) {
      push("markdownContent", "交付正文模板", source.markdownContent);
      push("fileNameTemplate", "文件名模板", source.fileNameTemplate);
      return fields;
    }
    if (isExcelCapabilityDeliveryConfig(source)) {
      push("fileNameTemplate", "文件名模板", source.fileNameTemplate);
      readMapList(source.excelSheets).forEach((sheet, index) => {
        const sheetName = readString(sheet.name, `Sheet ${index + 1}`);
        push(`excelSheets.${index}.bodyTemplate`, `${sheetName}正文模板`, sheet.bodyTemplate);
      });
      return fields;
    }
    push("deliveryContent", "交付内容", source.deliveryContent);
    push("deliveryTarget", "交付内容", source.deliveryTarget);
    push("body", "交付内容", source.body);
    return fields;
  }

  if (brickType === "agent") {
    push("systemPrompt", "系统提示词", source.systemPrompt);
    push("userPrompt", "用户提示词", source.userPrompt);
    return fields;
  }

  if (brickType === "cluster") {
    push("mergeRule", "拼接规则", source.mergeRule);
    push("intentInputTemplate", "待判断内容", source.intentInputTemplate);
    readMapList(source.intentRoutes).forEach((route, index) => {
      push(`intentRoutes.${index}.intentDescription`, `意图 ${index + 1}命中说明`, route.intentDescription);
    });
    readMapList(source.clusterAgents).forEach((agent, index) => {
      const agentName = readString(agent.name, `子智能体 ${index + 1}`);
      push(`clusterAgents.${index}.systemPrompt`, `${agentName}系统提示词`, agent.systemPrompt);
      push(`clusterAgents.${index}.userPrompt`, `${agentName}用户提示词`, agent.userPrompt);
    });
    return fields;
  }

  readMapList(source.inputFields).forEach((field, index) => {
    push(`inputFields.${index}.placeholder`, `输入框 ${index + 1}占位提示`, field.placeholder);
    push(`inputFields.${index}.defaultValue`, `输入框 ${index + 1}默认值`, field.defaultValue);
  });
  return fields;
}

export function collectRuntimeTemplateVariableNames(
  config: Record<string, unknown> | undefined,
  brickType: VisibleBrickType,
): string[] {
  const names = new Set<string>();
  collectRuntimeTemplateTextFields(config, brickType).forEach((field) => {
    extractTemplateVariableNames(field.text).forEach((name) => names.add(name));
  });
  return [...names];
}

/** @deprecated 请使用 collectRuntimeTemplateVariableNames，整份 config 扫描会误报设计态字段。 */
export function collectConfigTemplateVariableNames(config: Record<string, unknown> | undefined): string[] {
  return collectRuntimeTemplateVariableNames(config, "delivery");
}

export function collectUpstreamOutputVariables(
  visibleNodes: ValidatableWorkflowNode[],
  nodeIndex: number,
): Set<string> {
  const upstream = new Set<string>();
  for (let index = 0; index < nodeIndex; index += 1) {
    visibleNodes[index].data.outputVariables.forEach((name) => {
      if (name) {
        upstream.add(name);
      }
    });
  }
  return upstream;
}

export function findUnresolvedTemplateVariables(
  config: Record<string, unknown> | undefined,
  brickType: VisibleBrickType,
  upstreamVariables: Set<string>,
): string[] {
  return collectRuntimeTemplateVariableNames(config, brickType).filter(
    (name) => !WORKFLOW_SYSTEM_TEMPLATE_VARIABLES.has(name) && !upstreamVariables.has(name),
  );
}

function findUnresolvedTemplateVariableIssues(
  config: Record<string, unknown> | undefined,
  brickType: VisibleBrickType,
  upstreamVariables: Set<string>,
  subjectPrefix: string,
): WorkflowNodeValidationIssue[] {
  const unresolvedByVariable = new Map<string, Set<string>>();
  collectRuntimeTemplateTextFields(config, brickType).forEach((field) => {
    extractTemplateVariableNames(field.text).forEach((name) => {
      if (WORKFLOW_SYSTEM_TEMPLATE_VARIABLES.has(name) || upstreamVariables.has(name)) {
        return;
      }
      const fieldLabels = unresolvedByVariable.get(name) ?? new Set<string>();
      fieldLabels.add(field.label);
      unresolvedByVariable.set(name, fieldLabels);
    });
  });

  if (unresolvedByVariable.size === 0) {
    return [];
  }

  const details = [...unresolvedByVariable.entries()]
    .map(([name, fieldLabels]) => `${name}（见于：${[...fieldLabels].join("、")}）`)
    .join("；");

  return [issue(
    "WORKFLOW_NODE_TEMPLATE_VARIABLE_UNRESOLVED",
    `${subjectPrefix}引用了当前不可用的变量：${details}`,
  )];
}

function validateInputNode(node: ValidatableWorkflowNode): WorkflowNodeValidationIssue[] {
  const issues: WorkflowNodeValidationIssue[] = [];
  const fields = readMapList(node.data.rawConfig?.inputFields);
  if (fields.length === 0) {
    issues.push(issue("WORKFLOW_NODE_INPUT_FIELDS_REQUIRED", "至少需要配置一个输入字段"));
    return issues;
  }

  const fieldVariables = new Set<string>();
  for (const field of fields) {
    const variable = readString(field.variable);
    if (!VARIABLE_NAME_PATTERN.test(variable)) {
      issues.push(issue("WORKFLOW_NODE_INPUT_FIELD_VARIABLE_INVALID", `输入字段变量名不合法：${variable || "（空）"}`));
      continue;
    }
    fieldVariables.add(variable);

    const fieldType = readString(field.fieldType, "text");
    if (fieldType === "select") {
      const validOptions = normalizeInputFieldOptions(field.options, readString(field.placeholder));
      if (validOptions.length === 0) {
        const fieldLabel = readString(field.label, variable || "未命名字段");
        issues.push(issue("WORKFLOW_NODE_INPUT_FIELD_OPTIONS_REQUIRED", `下拉字段「${fieldLabel}」至少需要配置一个有效选项`));
      }
    }
  }

  const declaredOutputs = new Set(node.data.outputVariables.filter(Boolean));
  if (fieldVariables.size !== declaredOutputs.size
    || [...fieldVariables].some((name) => !declaredOutputs.has(name))) {
    issues.push(issue("WORKFLOW_NODE_INPUT_OUTPUT_MISMATCH", "输入字段变量必须与节点输出变量保持一致"));
  }

  return issues;
}

function validateAgentConfig(
  config: Record<string, unknown>,
  subject: string,
): WorkflowNodeValidationIssue[] {
  const promptError = validateCustomPromptConfiguration({
    systemPromptTemplateId: readString(config.systemPromptTemplateId, readString(config.promptTemplateId, "none")),
    userPromptTemplateId: readString(config.userPromptTemplateId, "none"),
    promptTemplateId: readString(config.promptTemplateId, "none"),
    systemPrompt: readString(config.systemPrompt),
    userPrompt: readString(config.userPrompt),
  });
  if (promptError) {
    const message = subject ? `${subject}：${promptError}` : promptError;
    return [issue("WORKFLOW_NODE_AGENT_PROMPT_INVALID", message)];
  }
  return [];
}

function validateAgentNode(node: ValidatableWorkflowNode): WorkflowNodeValidationIssue[] {
  return validateAgentConfig(node.data.rawConfig ?? {}, "");
}

function validateClusterNode(node: ValidatableWorkflowNode): WorkflowNodeValidationIssue[] {
  const issues: WorkflowNodeValidationIssue[] = [];
  const agents = readMapList(node.data.rawConfig?.clusterAgents);
  if (agents.length === 0) {
    issues.push(issue("WORKFLOW_NODE_CLUSTER_AGENTS_REQUIRED", "至少需要配置一个子智能体"));
    return issues;
  }

  const executionMode = normalizeClusterExecutionMode(node.data.rawConfig?.executionMode);
  const agentOutputs = new Set<string>();
  const agentIds = new Set<string>();
  agents.forEach((agent, index) => {
    const agentName = readString(agent.name, `子智能体 ${index + 1}`);
    issues.push(...validateAgentConfig(agent, `子智能体「${agentName}」`));
    const agentId = readString(agent.id);
    if (agentId) {
      agentIds.add(agentId);
    }

    const output = readString(agent.output);
    if (!VARIABLE_NAME_PATTERN.test(output)) {
      issues.push(issue("WORKFLOW_NODE_CLUSTER_AGENT_OUTPUT_INVALID", `子智能体「${agentName}」的输出变量名不合法`));
      return;
    }
    if (agentOutputs.has(output)) {
      issues.push(issue("WORKFLOW_NODE_CLUSTER_AGENT_OUTPUT_DUPLICATED", `存在重复的子智能体输出变量：${output}`));
      return;
    }
    agentOutputs.add(output);

  });

  const declaredOutputs = new Set(node.data.outputVariables.filter(Boolean));
  const clusterOutputVariable = readString(node.data.rawConfig?.clusterOutputVariable, "cluster_result");
  if (executionMode === "intent") {
    if (declaredOutputs.size !== 1 || !declaredOutputs.has(clusterOutputVariable)) {
      issues.push(issue("WORKFLOW_NODE_CLUSTER_INTENT_OUTPUT_INVALID", `意图分派模式下，下游输出必须统一为 ${clusterOutputVariable}`));
    }
    if (!readString(node.data.rawConfig?.intentInputTemplate)) {
      issues.push(issue("WORKFLOW_NODE_CLUSTER_INTENT_INPUT_REQUIRED", "意图分派模式下必须配置待判断内容"));
    }
    const selectionMode = readString(node.data.rawConfig?.intentSelectionMode, "multiple");
    if (selectionMode !== "single" && selectionMode !== "multiple") {
      issues.push(issue("WORKFLOW_NODE_CLUSTER_INTENT_SELECTION_MODE_INVALID", "意图命中策略不合法"));
    }
    const fallbackMode = readString(node.data.rawConfig?.intentFallbackMode, "fail");
    if (fallbackMode !== "fail" && fallbackMode !== "agent" && fallbackMode !== "fixed_reply") {
      issues.push(issue("WORKFLOW_NODE_CLUSTER_INTENT_FALLBACK_MODE_INVALID", "未命中处理方式不合法"));
    }
    const routes = readMapList(node.data.rawConfig?.intentRoutes);
    if (routes.length === 0) {
      issues.push(issue("WORKFLOW_NODE_CLUSTER_INTENT_ROUTES_REQUIRED", "至少需要配置一个意图"));
    }
    const intentCodes = new Set<string>();
    routes.forEach((route, index) => {
      const intentCode = readString(route.intentCode);
      if (!VARIABLE_NAME_PATTERN.test(intentCode)) {
        issues.push(issue("WORKFLOW_NODE_CLUSTER_INTENT_CODE_INVALID", `第 ${index + 1} 个意图代码不合法`));
      } else if (intentCodes.has(intentCode)) {
        issues.push(issue("WORKFLOW_NODE_CLUSTER_INTENT_CODE_DUPLICATED", `存在重复的意图代码：${intentCode}`));
      }
      intentCodes.add(intentCode);
      if (!readString(route.intentDescription)) {
        issues.push(issue("WORKFLOW_NODE_CLUSTER_INTENT_DESCRIPTION_REQUIRED", `第 ${index + 1} 个意图必须说明什么时候命中`));
      }
      const agentId = readString(route.agentId);
      if (!agentId || !agentIds.has(agentId)) {
        issues.push(issue("WORKFLOW_NODE_CLUSTER_INTENT_AGENT_MISSING", `第 ${index + 1} 个意图必须选择目标智能体`));
      }
    });
    if (fallbackMode === "agent" && !agentIds.has(readString(node.data.rawConfig?.fallbackAgentId))) {
      issues.push(issue("WORKFLOW_NODE_CLUSTER_INTENT_FALLBACK_AGENT_MISSING", "其他情况必须选择目标智能体"));
    }
    if (fallbackMode === "fixed_reply" && !readString(node.data.rawConfig?.fallbackReply)) {
      issues.push(issue("WORKFLOW_NODE_CLUSTER_INTENT_FALLBACK_REPLY_REQUIRED", "其他情况固定话术不能为空"));
    }
  } else {
    const expectedOutputs = new Set([clusterOutputVariable, ...agentOutputs]);
    if (expectedOutputs.size !== declaredOutputs.size || [...expectedOutputs].some((name) => !declaredOutputs.has(name))) {
      issues.push(issue("WORKFLOW_NODE_CLUSTER_OUTPUT_MISMATCH", "集群最终输出和子智能体输出必须与节点输出变量保持一致"));
    }
  }

  const rawExecutionMode = readString(node.data.rawConfig?.executionMode, "collaborative");
  if (!["collaborative", "relay", "intent"].includes(rawExecutionMode)) {
    issues.push(issue("WORKFLOW_NODE_CLUSTER_EXECUTION_MODE_INVALID", "执行方式仅支持协同处理、接力处理或意图分派"));
  }

  return issues;
}

function findClusterTemplateVariableIssues(
  config: Record<string, unknown> | undefined,
  upstreamVariables: Set<string>,
): WorkflowNodeValidationIssue[] {
  const source = config ?? {};
  const agents = readMapList(source.clusterAgents);
  const mode = normalizeClusterExecutionMode(source.executionMode);
  const issues: WorkflowNodeValidationIssue[] = [];

  const mergeRule = readString(source.mergeRule);
  if (mergeRule) {
    const childOutputs = new Set(agents.map((agent) => readString(agent.output)).filter(Boolean));
    const unresolved = extractTemplateVariableNames(mergeRule)
      .filter((name) => !childOutputs.has(name));
    if (unresolved.length > 0) {
      issues.push(issue("WORKFLOW_NODE_TEMPLATE_VARIABLE_UNRESOLVED", `集群输出模板只能引用子智能体输出变量：${[...new Set(unresolved)].join("、")}`));
    }
  }

  const intentFields = [
    { key: "intentInputTemplate", label: "待判断内容", text: readString(source.intentInputTemplate) },
    ...readMapList(source.intentRoutes).map((route, index) => ({
      key: `intentRoutes.${index}.intentDescription`,
      label: `意图 ${index + 1}命中说明`,
      text: readString(route.intentDescription),
    })),
  ];
  const intentUnresolved = collectTemplateUnresolvedVariables(intentFields, upstreamVariables);
  if (intentUnresolved.length > 0) {
    issues.push(issue("WORKFLOW_NODE_TEMPLATE_VARIABLE_UNRESOLVED", `意图配置引用了当前不可用的变量：${intentUnresolved.join("、")}`));
  }

  agents.forEach((agent, index) => {
    const agentName = readString(agent.name, `子智能体 ${index + 1}`);
    const available = new Set(upstreamVariables);
    if (mode === "relay") {
      agents.slice(0, index).forEach((previous) => {
        const output = readString(previous.output);
        if (output) {
          available.add(output);
        }
      });
    }
    const unresolved = collectTemplateUnresolvedVariables(
      [
        { key: `clusterAgents.${index}.systemPrompt`, label: "系统提示词", text: readString(agent.systemPrompt) },
        { key: `clusterAgents.${index}.userPrompt`, label: "用户提示词", text: readString(agent.userPrompt) },
      ],
      available,
    );
    if (unresolved.length > 0) {
      issues.push(issue("WORKFLOW_NODE_TEMPLATE_VARIABLE_UNRESOLVED", `子智能体「${agentName}」引用了当前执行顺序下不可用的变量：${unresolved.join("、")}`));
    }
  });

  return issues;
}

function findDeliveryTemplateVariableIssues(
  config: Record<string, unknown>,
  upstreamVariables: Set<string>,
): WorkflowNodeValidationIssue[] {
  if (readString(config.deliveryConfigMode, "single") !== "multiple") {
    return findUnresolvedTemplateVariableIssues(config, "delivery", upstreamVariables, "交付模板");
  }

  const executionPolicy = readString(config.deliveryExecutionPolicy, "all");
  const issues: WorkflowNodeValidationIssue[] = [];
  readMapList(config.deliveryItems).forEach((item, index) => {
    const itemName = readString(item.name, `交付项 ${index + 1}`);
    const itemConfig = readMap(item.config);
    const itemFields = collectRuntimeTemplateTextFields(
      { deliveryConfigMode: "multiple", deliveryItems: [{ ...item, config: itemConfig }] },
      "delivery",
    ).filter((field) => field.key.startsWith("deliveryItems.0."));
    const availableVariables = new Set(upstreamVariables);
    if (executionPolicy === "conditional") {
      collectDeliveryTriggerVariableNames(readMap(item.triggerRule), "conditional")
        .forEach((name) => availableVariables.add(name));
    }
    const unresolved = collectTemplateUnresolvedVariables(itemFields, availableVariables);
    if (unresolved.length > 0) {
      issues.push(issue(
        "WORKFLOW_NODE_TEMPLATE_VARIABLE_UNRESOLVED",
        `${itemName}交付模板引用了当前不可用的变量：${unresolved.join("；")}`,
      ));
    }
  });
  return issues;
}

function collectTemplateUnresolvedVariables(
  fields: TemplateTextField[],
  availableVariables: Set<string>,
): string[] {
  const unresolved = new Set<string>();
  fields.forEach((field) => {
    extractTemplateVariableNames(field.text).forEach((name) => {
      if (!WORKFLOW_SYSTEM_TEMPLATE_VARIABLES.has(name) && !availableVariables.has(name)) {
        unresolved.add(`${name}（见于：${field.label}）`);
      }
    });
  });
  return [...unresolved];
}

function isWordDeliveryConfig(config: Record<string, unknown>): boolean {
  return isWordCapabilityDeliveryConfig(config);
}

function isExcelDeliveryConfig(config: Record<string, unknown>): boolean {
  return isExcelCapabilityDeliveryConfig(config);
}

function validateDeliveryNode(
  node: ValidatableWorkflowNode,
  upstreamVariables: Set<string>,
): WorkflowNodeValidationIssue[] {
  const issues: WorkflowNodeValidationIssue[] = [];
  const config = node.data.rawConfig ?? {};
  const deliveryMode = readString(config.deliveryMode, "direct");
  const isDirect = deliveryMode === "direct" || readString(config.deliveryType) === "direct";

  if (readString(config.deliveryConfigMode, "single") === "multiple") {
    const items = readMapList(config.deliveryItems);
    if (items.length === 0) {
      issues.push(issue("WORKFLOW_NODE_DELIVERY_ITEMS_REQUIRED", "必须至少配置一个交付项"));
    }
    const executionPolicy = readString(config.deliveryExecutionPolicy, "all");
    items.forEach((item, index) => {
      const rawItemName = typeof item.name === "string" ? item.name.trim() : "";
      const itemName = rawItemName || `交付项 ${index + 1}`;
      const itemConfig = readMap(item.config);
      const itemIsDirect = inferDeliveryMode(itemConfig, deliveryMode) === "direct" || readString(itemConfig.deliveryType) === "direct";
      if (!rawItemName) {
        issues.push(issue("WORKFLOW_NODE_DELIVERY_ITEM_NAME_REQUIRED", `第 ${index + 1} 个交付项必须填写名称`));
      }
      if (itemIsDirect) {
        const deliveryContent = readString(itemConfig.deliveryContent)
          || readString(itemConfig.deliveryTarget)
          || readString(itemConfig.body);
        if (!deliveryContent) {
          issues.push(issue("WORKFLOW_NODE_DELIVERY_DIRECT_CONTENT_REQUIRED", `${itemName}必须配置直接交付内容模板`));
        }
      } else {
        const capabilityId = readString(itemConfig.deliveryCapabilityId);
        if (isSentinelId(capabilityId)) {
          issues.push(issue("WORKFLOW_NODE_DELIVERY_CAPABILITY_REQUIRED", `${itemName}必须选择交付能力`));
        }
        if (isWordDeliveryConfig(itemConfig) && !readString(itemConfig.markdownContent)) {
          issues.push(issue("WORKFLOW_NODE_DELIVERY_MARKDOWN_REQUIRED", `${itemName}必须配置 Word 交付正文模板`));
        }
        if (isExcelDeliveryConfig(itemConfig)) {
          const sheets = readMapList(itemConfig.excelSheets);
          if (sheets.length === 0) {
            issues.push(issue("WORKFLOW_NODE_DELIVERY_EXCEL_SHEETS_REQUIRED", `${itemName}必须至少配置一个 Excel Sheet 页`));
          }
          sheets.forEach((sheet, sheetIndex) => {
            const sheetName = readString(sheet.name, `Sheet ${sheetIndex + 1}`);
            if (!readString(sheet.name)) {
              issues.push(issue("WORKFLOW_NODE_DELIVERY_EXCEL_SHEET_NAME_REQUIRED", `${itemName}的第 ${sheetIndex + 1} 个 Sheet 必须填写名称`));
            }
            if (!readString(sheet.bodyTemplate)) {
              issues.push(issue("WORKFLOW_NODE_DELIVERY_EXCEL_SHEET_TEMPLATE_REQUIRED", `${itemName}的 Sheet「${sheetName}」必须配置正文模板`));
            }
          });
        }
      }
      if (executionPolicy === "conditional") {
        const triggerRule = readMap(item.triggerRule);
        const triggerType = readString(triggerRule.type, "always");
        if (triggerType === "cluster_agent_matched") {
          if (!readString(triggerRule.clusterNodeId) || !readString(triggerRule.agentId) || !readString(triggerRule.variableName)) {
            issues.push(issue("WORKFLOW_NODE_DELIVERY_TRIGGER_AGENT_REQUIRED", `${itemName}的触发规则必须选择智能体集群中的子智能体`));
          }
        } else if (triggerType === "input_field_equals") {
          if (!readString(triggerRule.inputNodeId) || !readString(triggerRule.variableName) || !readString(triggerRule.expectedValue)) {
            issues.push(issue("WORKFLOW_NODE_DELIVERY_TRIGGER_INPUT_REQUIRED", `${itemName}的触发规则必须选择输入字段并填写固定值`));
          }
        } else if (triggerType === "agent_output_exists") {
          if (!readString(triggerRule.agentNodeId) || !readString(triggerRule.variableName)) {
            issues.push(issue("WORKFLOW_NODE_DELIVERY_TRIGGER_AGENT_OUTPUT_REQUIRED", `${itemName}的触发规则必须选择单智能体输出`));
          }
        } else if (triggerType !== "always") {
          issues.push(issue("WORKFLOW_NODE_DELIVERY_TRIGGER_INVALID", `${itemName}的触发规则不合法`));
        }
      }
    });
  } else if (isDirect) {
    const deliveryContent = readString(config.deliveryContent)
      || readString(config.deliveryTarget)
      || readString(config.body);
    if (!deliveryContent) {
      issues.push(issue("WORKFLOW_NODE_DELIVERY_DIRECT_CONTENT_REQUIRED", "必须配置直接交付内容模板"));
    }
  } else {
    const capabilityId = readString(config.deliveryCapabilityId);
    if (isSentinelId(capabilityId)) {
      issues.push(issue("WORKFLOW_NODE_DELIVERY_CAPABILITY_REQUIRED", "必须选择交付能力"));
    }
    if (isWordDeliveryConfig(config)) {
      const markdownContent = readString(config.markdownContent);
      if (!markdownContent) {
        issues.push(issue("WORKFLOW_NODE_DELIVERY_MARKDOWN_REQUIRED", "必须配置 Word 交付正文模板"));
      }
    }
    if (isExcelDeliveryConfig(config)) {
      const sheets = readMapList(config.excelSheets);
      if (sheets.length === 0) {
        issues.push(issue("WORKFLOW_NODE_DELIVERY_EXCEL_SHEETS_REQUIRED", "必须至少配置一个 Excel Sheet 页"));
      }
      sheets.forEach((sheet, index) => {
        const sheetName = readString(sheet.name, `Sheet ${index + 1}`);
        if (!readString(sheet.name)) {
          issues.push(issue("WORKFLOW_NODE_DELIVERY_EXCEL_SHEET_NAME_REQUIRED", `第 ${index + 1} 个 Sheet 必须填写名称`));
        }
        if (!readString(sheet.bodyTemplate)) {
          issues.push(issue("WORKFLOW_NODE_DELIVERY_EXCEL_SHEET_TEMPLATE_REQUIRED", `Sheet「${sheetName}」必须配置正文模板`));
        }
      });
    }
  }

  issues.push(...findDeliveryTemplateVariableIssues(config, upstreamVariables));

  return issues;
}

export function validateWorkflowNode(
  node: ValidatableWorkflowNode,
  upstreamVariables: Set<string>,
): WorkflowNodeValidationIssue[] {
  const brickType = inferBrickType(node);
  if (brickType === "input") {
    return validateInputNode(node);
  }
  if (brickType === "agent") {
    const issues = validateAgentNode(node);
    issues.push(...findUnresolvedTemplateVariableIssues(node.data.rawConfig, brickType, upstreamVariables, "提示词"));
    return issues;
  }
  if (brickType === "cluster") {
    const issues = validateClusterNode(node);
    issues.push(...findClusterTemplateVariableIssues(node.data.rawConfig, upstreamVariables));
    return issues;
  }
  return validateDeliveryNode(node, upstreamVariables);
}

export function validateWorkflowDeliveryPlacement(
  visibleNodes: ValidatableWorkflowNode[],
): WorkflowNodeValidationIssue[] {
  const deliveryNodes = visibleNodes.filter((node) => inferBrickType(node) === "delivery");
  const issues: WorkflowNodeValidationIssue[] = [];
  if (deliveryNodes.length === 0) {
    issues.push(issue("WORKFLOW_VALIDATION_DELIVERY_REQUIRED", "流程必须包含一个交付节点"));
  }
  if (deliveryNodes.length > 1) {
    issues.push(issue("WORKFLOW_VALIDATION_DELIVERY_DUPLICATED", "流程只能包含一个交付节点"));
  }
  const lastNode = visibleNodes[visibleNodes.length - 1];
  if (deliveryNodes.length > 0 && lastNode && inferBrickType(lastNode) !== "delivery") {
    issues.push(issue("WORKFLOW_VALIDATION_DELIVERY_MUST_BE_LAST", "交付节点必须放在流程最后一步"));
  }
  return issues;
}

export function canInsertWorkflowBrick(
  visibleNodes: ValidatableWorkflowNode[],
  brickType: VisibleBrickType,
  insertAfterNodeId?: string,
): string | null {
  const hasDelivery = visibleNodes.some((node) => inferBrickType(node) === "delivery");
  const insertAfterIndex = insertAfterNodeId ? visibleNodes.findIndex((node) => node.id === insertAfterNodeId) : visibleNodes.length - 1;
  const insertAfterNode = insertAfterIndex >= 0 ? visibleNodes[insertAfterIndex] : null;
  const insertingAtEnd = insertAfterIndex >= visibleNodes.length - 1;
  if (brickType === "delivery" && hasDelivery) {
    return "流程只能有一个交付节点";
  }
  if (insertAfterNode && inferBrickType(insertAfterNode) === "delivery") {
    return "交付节点必须保持在流程最后一步，不能在其后添加积木";
  }
  if (brickType === "delivery" && !insertingAtEnd) {
    return "交付节点必须放在流程最后一步，请先选中当前最后一步再添加";
  }
  return null;
}

export function canAppendWorkflowBrick(
  visibleNodes: ValidatableWorkflowNode[],
  brickType: VisibleBrickType,
): string | null {
  return canInsertWorkflowBrick(visibleNodes, brickType, visibleNodes[visibleNodes.length - 1]?.id);
}

export function canMoveWorkflowNode(
  visibleNodes: ValidatableWorkflowNode[],
  nodeId: string,
  direction: -1 | 1,
): string | null {
  const currentIndex = visibleNodes.findIndex((node) => node.id === nodeId);
  const nextIndex = currentIndex + direction;
  if (currentIndex < 0 || nextIndex < 0 || nextIndex >= visibleNodes.length) {
    return null;
  }
  const movingNode = visibleNodes[currentIndex];
  const targetNode = visibleNodes[nextIndex];
  if (inferBrickType(movingNode) === "delivery") {
    return "交付节点必须保持在最后一步";
  }
  if (direction === 1 && inferBrickType(targetNode) === "delivery") {
    return "不能把其他积木移动到交付节点之后";
  }
  return null;
}

export function buildWorkflowNodeValidationMap(
  visibleNodes: ValidatableWorkflowNode[],
): Map<string, WorkflowNodeValidationIssue[]> {
  const validationMap = new Map<string, WorkflowNodeValidationIssue[]>();
  visibleNodes.forEach((node, index) => {
    const upstream = collectUpstreamOutputVariables(visibleNodes, index);
    validationMap.set(node.id, validateWorkflowNode(node, upstream));
  });
  return validationMap;
}

export function applyValidatedConfigStatus<T extends ValidatableWorkflowNode>(visibleNodes: T[]): T[] {
  const validationMap = buildWorkflowNodeValidationMap(visibleNodes);
  return visibleNodes.map((node) => ({
    ...node,
    data: {
      ...node.data,
      configStatus: (validationMap.get(node.id)?.length ?? 0) === 0 ? "complete" : "incomplete",
    },
  })) as T[];
}

type VariableImpact = {
  nodeId: string;
  nodeLabel: string;
  variables: string[];
};

function collectVariableImpacts(
  visibleNodes: ValidatableWorkflowNode[],
  predicate: (node: ValidatableWorkflowNode, upstream: Set<string>, index: number) => string[],
): VariableImpact[] {
  const impacts: VariableImpact[] = [];
  visibleNodes.forEach((node, index) => {
    const upstream = collectUpstreamOutputVariables(visibleNodes, index);
    const variables = [...new Set(predicate(node, upstream, index))].filter(Boolean);
    if (variables.length > 0) {
      impacts.push({
        nodeId: node.id,
        nodeLabel: node.data.label,
        variables,
      });
    }
  });
  return impacts;
}

function formatImpactMessage(title: string, impacts: VariableImpact[], footer: string): string {
  if (impacts.length === 0) {
    return "";
  }
  const lines = impacts.map((impact) => (
    `· ${impact.nodeLabel}：${impact.variables.map((name) => `{{${name}}}`).join("、")}`
  ));
  return `${title}\n${lines.join("\n")}\n\n${footer}`;
}

export function describeDeleteNodeVariableImpact(
  visibleNodes: ValidatableWorkflowNode[],
  nodeIdToDelete: string,
): string {
  const targetIndex = visibleNodes.findIndex((node) => node.id === nodeIdToDelete);
  if (targetIndex < 0) {
    return "";
  }

  const removedOutputs = new Set(
    visibleNodes[targetIndex].data.outputVariables.filter(Boolean),
  );
  if (removedOutputs.size === 0) {
    return "";
  }

  const impacts = collectVariableImpacts(visibleNodes, (node, _upstream, index) => {
    if (index <= targetIndex) {
      return [];
    }
    const brickType = inferBrickType(node);
    return collectRuntimeTemplateVariableNames(node.data.rawConfig, brickType).filter((name) => removedOutputs.has(name));
  });

  return formatImpactMessage(
    "以下下游积木仍引用将被删除节点的输出变量：",
    impacts,
    "删除后这些引用将失效，请确认是否继续。",
  );
}

function collectUnresolvedTemplateVariableNames(
  node: ValidatableWorkflowNode,
  orderedNodes: ValidatableWorkflowNode[],
): Set<string> {
  const nodeIndex = orderedNodes.findIndex((item) => item.id === node.id);
  if (nodeIndex < 0) {
    return new Set();
  }
  const upstream = collectUpstreamOutputVariables(orderedNodes, nodeIndex);
  const brickType = inferBrickType(node);
  return new Set(findUnresolvedTemplateVariables(node.data.rawConfig, brickType, upstream));
}

export function describeMoveNodeVariableImpact(
  visibleNodes: ValidatableWorkflowNode[],
  nodeId: string,
  direction: -1 | 1,
): string {
  const currentIndex = visibleNodes.findIndex((node) => node.id === nodeId);
  const nextIndex = currentIndex + direction;
  if (currentIndex < 0 || nextIndex < 0 || nextIndex >= visibleNodes.length) {
    return "";
  }

  const reordered = [...visibleNodes];
  const [movingNode] = reordered.splice(currentIndex, 1);
  reordered.splice(nextIndex, 0, movingNode);

  const impacts: VariableImpact[] = [];
  reordered.forEach((node) => {
    const beforeUnresolved = collectUnresolvedTemplateVariableNames(node, visibleNodes);
    const afterUnresolved = collectUnresolvedTemplateVariableNames(node, reordered);
    const newlyBroken = [...afterUnresolved].filter((name) => !beforeUnresolved.has(name));
    if (newlyBroken.length > 0) {
      impacts.push({
        nodeId: node.id,
        nodeLabel: node.data.label,
        variables: newlyBroken,
      });
    }
  });

  return formatImpactMessage(
    "移动后以下积木将失去可用的变量引用：",
    impacts,
    "继续移动可能导致运行时无法解析模板变量。",
  );
}

export function summarizeValidationIssues(issues: WorkflowNodeValidationIssue[]): string {
  if (issues.length === 0) {
    return "";
  }
  return issues.map((item) => item.message).join("；");
}

type WorkflowSaveWarningNode = {
  id: string;
  data: {
    label: string;
  };
};

/** 汇总保存前可跳过的草稿级配置提醒，供二次确认弹窗展示。 */
export function buildWorkflowSaveWarningMessage(
  incompleteNodes: WorkflowSaveWarningNode[],
  nodeValidationMap: Map<string, WorkflowNodeValidationIssue[]>,
  deliveryPlacementIssues: WorkflowNodeValidationIssue[],
): string | null {
  const lines: string[] = [];

  if (deliveryPlacementIssues.length > 0) {
    lines.push(summarizeValidationIssues(deliveryPlacementIssues));
  }

  incompleteNodes.forEach((node) => {
    const issues = nodeValidationMap.get(node.id) ?? [];
    if (issues.length > 0) {
      lines.push(`「${node.data.label}」：${summarizeValidationIssues(issues)}`);
    }
  });

  if (lines.length === 0) {
    return null;
  }

  return lines.join("\n");
}
