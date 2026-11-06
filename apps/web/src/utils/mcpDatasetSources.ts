import type { TemplateSource } from "../types/delivery-template";

type Node = { id: string; data: { label: string; nodeType: string; rawConfig?: Record<string, unknown> } };
type Capability = { id: string; name: string; assetType: string; config: Record<string, unknown> };
/** 候选来源来自上游智能体实际配置的 MCP 工具规则，输入节点及集群聚合文本不充当数据集。 */
export function buildMcpDatasetSources(nodes: Node[], capabilities: Capability[], targetNodeId: string): (TemplateSource & { type: "array"; sensitive: boolean; deliverable: boolean; description: string })[] {
  const current = nodes.findIndex(node => node.id === targetNodeId);
  return nodes.slice(0, Math.max(0, current)).flatMap(node => {
    const config = node.data.rawConfig ?? {};
    const agents = node.data.nodeType === "agent" ? [{ config, prefix: "", label: node.data.label }]
      : node.data.nodeType === "parallel_group" && Array.isArray(config.clusterAgents) ? config.clusterAgents.map((value, index) => ({ config: value as Record<string, unknown>, prefix: `member_${index}.`, label: `${node.data.label} → ${String((value as Record<string, unknown>).name || `智能体 ${index + 1}`)}` })) : [];
    return agents.flatMap(agent => {
      const base = capabilities.find(capability => capability.id === agent.config.agentAssetId)?.config ?? {};
      const effective = { ...base, ...agent.config };
      const ids = Array.isArray(effective.mcpIds) ? effective.mcpIds : [];
      return capabilities.filter(capability => capability.assetType === "mcp" && ids.includes(capability.id)).flatMap(capability => Object.entries((capability.config.toolResultPolicies ?? {}) as Record<string, Record<string, unknown>>).filter(([, policy]) => (policy.mode === "dataset" || policy.mode === "auto")).map(([tool, policy]) => ({
        name: "mcp_datasets", sourceNodeId: node.id, sourceNodeName: `${agent.label} → ${capability.name} → ${tool}`,
        type: "array" as const, sensitive: false, deliverable: false, description: "MCP 工具完整明细", datasetPath: `${agent.prefix}cap_${capability.id.replace(/-/g, "_")}.${String(policy.datasetKey || tool)}`,
        datasetColumns: Array.isArray(policy.columns) ? policy.columns.map(String) : [],
      })));
    });
  });
}
