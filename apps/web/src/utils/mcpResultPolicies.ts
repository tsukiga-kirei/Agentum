type Policy = Record<string, unknown>;

export function newMcpResultKey(tool: string): string {
  return tool.replace(/[^A-Za-z0-9_]/g, "_").replace(/^[^A-Za-z_]/, "result_").slice(0, 100);
}

/** 工具名是规则主键，复制必须绑定其他工具；结果名唯一，避免后一次取数覆盖前一份结果。 */
export function copyMcpResultPolicy(policies: Record<string, Policy>, source: string, target: string): Record<string, Policy> {
  if (!policies[source] || !target || target in policies) return policies;
  const used = new Set(Object.entries(policies).map(([name, policy]) => String(policy.datasetKey ?? name)));
  const base = newMcpResultKey(target);
  let key = base;
  let suffix = 2;
  while (used.has(key)) key = `${base.slice(0, 90)}_${suffix++}`;
  return { ...policies, [target]: { ...structuredClone(policies[source]), datasetKey: key } };
}
