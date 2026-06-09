/**
 * 在流式文本、完成摘要与持久化快照之间选取最完整的 Markdown 正文。
 * 避免 completed 事件用截断 summary 覆盖流式阶段已渲染的完整内容。
 */
export function pickBestAgentOutput(...candidates: Array<string | null | undefined>): string {
  const texts = candidates.filter((text): text is string => typeof text === "string" && text.trim().length > 0);
  if (texts.length === 0) {
    return "";
  }

  return texts.sort((left, right) => scoreAgentOutput(right) - scoreAgentOutput(left))[0];
}

function scoreAgentOutput(text: string): number {
  let score = text.length;
  if (text.includes("\n")) {
    score += 2000;
  }
  if (/^#{1,6}\s/m.test(text)) {
    score += 500;
  }
  if (/^[-*]\s/m.test(text)) {
    score += 300;
  }
  return score;
}
