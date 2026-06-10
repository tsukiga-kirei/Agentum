function readString(value: unknown): string {
  return value == null ? "" : String(value).trim();
}

/**
 * 解析节点/子智能体配置中的系统提示词，仅展示流程设计或运行快照中的实际配置。
 */
export function resolveSystemDisplayPrompt(config: Record<string, unknown> | null | undefined): string {
  const snapshot = config ?? {};
  const resolved = readString(snapshot.resolvedSystemPrompt);
  if (resolved) {
    return resolved;
  }
  return readString(snapshot.systemPrompt);
}

/**
 * 解析节点/子智能体配置中的用户提示词。
 */
export function resolveUserDisplayPrompt(config: Record<string, unknown> | null | undefined): string {
  const snapshot = config ?? {};
  const resolved = readString(snapshot.resolvedUserPrompt);
  if (resolved) {
    return resolved;
  }
  return readString(snapshot.userPrompt);
}

export function formatDisplayPrompt(value: string): string {
  return value.trim() || "（未配置）";
}
