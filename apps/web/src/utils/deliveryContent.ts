import type { RuntimePreviewStep } from "../types/runtime-types";

function readConfigString(value: unknown, fallback = ""): string {
  const text = String(value ?? "").trim();
  return text || fallback;
}

/** 从交付节点输出快照解析可展示的交付执行摘要。 */
export function resolveDeliveryDisplayContent(deliveryStep?: RuntimePreviewStep): string {
  if (!deliveryStep) {
    return "";
  }
  const payloadField = deliveryStep.outputs?.find((field) => field.label === "deliveryPayload")?.value;
  if (payloadField) {
    try {
      const parsed = JSON.parse(payloadField) as Record<string, unknown>;
      const body = readConfigString(parsed.body);
      const target = readConfigString(parsed.deliveryTarget);
      return body || target;
    } catch {
      return payloadField;
    }
  }
  const summary = deliveryStep.outputs?.find((field) => field.label === "summary")?.value;
  if (summary) {
    return summary;
  }
  return readConfigString(deliveryStep.configSnapshot?.markdownContent) || readConfigString(deliveryStep.configSnapshot?.deliveryTarget);
}
