/**
 * 条件触发的交付项在模板中可引用的额外变量。
 * 意图分派模式下子智能体 output 不会进入上游 outputVariables，但命中触发时运行态仍会写入该变量。
 */

export type DeliveryTriggerType = "always" | "cluster_agent_matched" | "input_field_equals" | "agent_output_exists";

export type DeliveryTriggerRuleLike = {
  type?: DeliveryTriggerType | string;
  clusterNodeId?: string;
  agentId?: string;
  inputNodeId?: string;
  agentNodeId?: string;
  variableName?: string;
};

export type DeliveryTriggerContextMeta = {
  clusterName?: string;
  agentName?: string;
  inputNodeName?: string;
  fieldLabel?: string;
  agentNodeName?: string;
};

export type DeliveryTriggerContextVariable = {
  name: string;
  sourceNodeId: string;
  sourceNodeName: string;
  type: "string";
  sensitive: false;
  deliverable: true;
  description: string;
};

function readText(value: unknown, fallback = ""): string {
  return typeof value === "string" ? value.trim() : fallback;
}

/** 从触发规则提取运行时可用的额外模板变量名。 */
export function collectDeliveryTriggerVariableNames(
  triggerRule: DeliveryTriggerRuleLike | undefined,
  executionPolicy: "all" | "conditional" = "conditional",
): string[] {
  if (executionPolicy !== "conditional" || !triggerRule) {
    return [];
  }
  const type = readText(triggerRule.type, "always") as DeliveryTriggerType;
  const variableName = readText(triggerRule.variableName);
  if (!variableName) {
    return [];
  }
  switch (type) {
    case "cluster_agent_matched":
      return readText(triggerRule.clusterNodeId) && readText(triggerRule.agentId) ? [variableName] : [];
    case "agent_output_exists":
      return readText(triggerRule.agentNodeId) ? [variableName] : [];
    case "input_field_equals":
      return readText(triggerRule.inputNodeId) ? [variableName] : [];
    default:
      return [];
  }
}

function buildTriggerVariableDescription(
  type: DeliveryTriggerType,
  variableName: string,
  meta: DeliveryTriggerContextMeta,
): { sourceNodeId: string; sourceNodeName: string; description: string } {
  switch (type) {
    case "cluster_agent_matched":
      return {
        sourceNodeId: readText(meta.clusterName, "cluster"),
        sourceNodeName: meta.agentName
          ? `子智能体「${meta.agentName}」`
          : "命中子智能体",
        description: `命中该子智能体时写入 {{${variableName}}}；仅在本交付项触发后可用`,
      };
    case "agent_output_exists":
      return {
        sourceNodeId: readText(meta.agentNodeName, "agent"),
        sourceNodeName: meta.agentNodeName ? `节点「${meta.agentNodeName}」` : "单智能体",
        description: `单智能体有输出时写入 {{${variableName}}}；仅在本交付项触发后可用`,
      };
    case "input_field_equals":
      return {
        sourceNodeId: readText(meta.inputNodeName, "input"),
        sourceNodeName: meta.fieldLabel
          ? `输入字段「${meta.fieldLabel}」`
          : "输入字段",
        description: `输入字段命中固定值时可用 {{${variableName}}}`,
      };
    default:
      return {
        sourceNodeId: "trigger",
        sourceNodeName: "触发上下文",
        description: `触发命中后可用 {{${variableName}}}`,
      };
  }
}

/** 构建交付模板编辑器中的「触发变量」列表；已在上游出现的变量不再重复展示。 */
export function buildDeliveryTriggerContextVariables(
  baseVariables: ReadonlyArray<{ name: string }>,
  triggerRule: DeliveryTriggerRuleLike | undefined,
  showTrigger: boolean,
  meta: DeliveryTriggerContextMeta = {},
): DeliveryTriggerContextVariable[] {
  if (!showTrigger) {
    return [];
  }
  const type = readText(triggerRule?.type, "always") as DeliveryTriggerType;
  if (type === "always") {
    return [];
  }
  const baseNameSet = new Set(baseVariables.map((variable) => variable.name));
  return collectDeliveryTriggerVariableNames(triggerRule, "conditional")
    .filter((name) => !baseNameSet.has(name))
    .map((name) => {
      const context = buildTriggerVariableDescription(type, name, meta);
      return {
        name,
        sourceNodeId: context.sourceNodeId,
        sourceNodeName: context.sourceNodeName,
        type: "string",
        sensitive: false,
        deliverable: true,
        description: context.description,
      };
    });
}

export function resolveDeliveryTriggerContextMeta(
  triggerRule: DeliveryTriggerRuleLike | undefined,
  options: {
    clusterAgentOptions?: ReadonlyArray<{
      clusterNodeId: string;
      agentId: string;
      clusterName: string;
      agentName: string;
    }>;
    inputFieldOptions?: ReadonlyArray<{
      inputNodeId: string;
      fieldLabel: string;
      inputNodeName: string;
    }>;
    agentOutputOptions?: ReadonlyArray<{
      agentNodeId: string;
      agentNodeName: string;
    }>;
  } = {},
): DeliveryTriggerContextMeta {
  const type = readText(triggerRule?.type, "always") as DeliveryTriggerType;
  if (type === "cluster_agent_matched") {
    const matched = options.clusterAgentOptions?.find(
      (item) => item.clusterNodeId === readText(triggerRule?.clusterNodeId)
        && item.agentId === readText(triggerRule?.agentId),
    );
    return {
      clusterName: matched?.clusterName,
      agentName: matched?.agentName,
    };
  }
  if (type === "input_field_equals") {
    const matched = options.inputFieldOptions?.find(
      (item) => item.inputNodeId === readText(triggerRule?.inputNodeId)
        && readText(triggerRule?.variableName),
    );
    return {
      inputNodeName: matched?.inputNodeName,
      fieldLabel: matched?.fieldLabel,
    };
  }
  if (type === "agent_output_exists") {
    const matched = options.agentOutputOptions?.find(
      (item) => item.agentNodeId === readText(triggerRule?.agentNodeId),
    );
    return {
      agentNodeName: matched?.agentNodeName,
    };
  }
  return {};
}
