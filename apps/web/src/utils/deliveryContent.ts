import type { RuntimeDeliveryItem, RuntimePreviewStep } from "../types/runtime-types";

function readConfigString(value: unknown, fallback = ""): string {
  const text = String(value ?? "").trim();
  return text || fallback;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function parseJsonValue(value: string): unknown {
  try {
    return JSON.parse(value);
  } catch {
    return value;
  }
}

function readOutputValue(step: RuntimePreviewStep, label: string): unknown {
  const field = step.outputs?.find((item) => item.label === label);
  if (!field?.value) {
    return undefined;
  }
  return parseJsonValue(field.value);
}

function readDirectContentFromPayload(payload: unknown): string {
  if (!isRecord(payload)) {
    return "";
  }
  return readConfigString(payload.body) || readConfigString(payload.deliveryTarget);
}

/** 将字节数格式化为可读文件大小。 */
export function formatDeliveryFileSize(sizeBytes: string | number): string {
  const bytes = typeof sizeBytes === "string" ? Number.parseInt(sizeBytes, 10) : sizeBytes;
  if (!Number.isFinite(bytes) || bytes < 0) {
    return "";
  }
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`;
  }
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function resolveDeliveryKind(
  adapter: string,
  fileName: string,
  content: string,
): RuntimeDeliveryItem["kind"] {
  if (adapter === "word_document" || adapter === "document" || fileName) {
    return "document";
  }
  if (adapter === "direct" || content) {
    return "direct";
  }
  return "other";
}

/** 将单条交付记录映射为运行态交付项视图。 */
export function mapDeliveryRecordToItem(record: Record<string, unknown>, index: number): RuntimeDeliveryItem {
  const deliveryResult = isRecord(record.deliveryResult) ? record.deliveryResult : {};
  const adapter = readConfigString(deliveryResult.adapter);
  const fileName = readConfigString(deliveryResult.fileName);
  const sizeBytes = readConfigString(deliveryResult.sizeBytes);
  const recordId = readConfigString(record.deliveryRecordId);
  const itemName = readConfigString(record.itemName, `交付项 ${index + 1}`);
  const status = readConfigString(record.deliveryStatus, "success");
  const content = readConfigString(deliveryResult.content) || readDirectContentFromPayload(record.deliveryPayload);
  const kind = resolveDeliveryKind(adapter, fileName, content);
  const formattedSize = formatDeliveryFileSize(sizeBytes);

  let meta = "";
  if (kind === "document") {
    const sizeLabel = formattedSize ? ` · ${formattedSize}` : "";
    meta = fileName && fileName !== itemName ? `${fileName}${sizeLabel}` : formattedSize;
  } else if (kind === "direct") {
    meta = "直接交付";
  } else {
    meta = readConfigString(record.summary, "交付项已执行");
  }

  return {
    key: readConfigString(record.itemId, recordId || `delivery-item-${index}`),
    name: itemName,
    status,
    meta,
    kind,
    content: kind === "direct" ? content : undefined,
    recordId: recordId || undefined,
    fileName: fileName || undefined,
    sizeBytes: sizeBytes || undefined,
    downloadUrl: readConfigString(deliveryResult.downloadUrl) || undefined,
    deliveryType: adapter || undefined,
  };
}

function mapSingleDeliveryOutputs(step: RuntimePreviewStep): RuntimeDeliveryItem | null {
  const deliveryResult = readOutputValue(step, "deliveryResult");
  const deliveryPayload = readOutputValue(step, "deliveryPayload");
  const recordId = readConfigString(readOutputValue(step, "deliveryRecordId"));
  const status = readConfigString(readOutputValue(step, "deliveryStatus"), step.state === "done" ? "success" : step.state);
  const summary = readConfigString(readOutputValue(step, "summary"));

  const resultRecord = isRecord(deliveryResult) ? deliveryResult : {};
  const content = readConfigString(resultRecord.content) || readDirectContentFromPayload(deliveryPayload);
  const fileName = readConfigString(resultRecord.fileName);
  const adapter = readConfigString(resultRecord.adapter);
  const kind = resolveDeliveryKind(adapter, fileName, content);

  if (!recordId && status === "skipped") {
    return null;
  }
  if (!content && !fileName && !recordId && !summary) {
    return null;
  }

  const formattedSize = formatDeliveryFileSize(readConfigString(resultRecord.sizeBytes));
  let meta = "";
  if (kind === "document") {
    meta = formattedSize;
  } else if (kind === "direct") {
    meta = "直接交付";
  } else {
    meta = summary || "交付项已执行";
  }

  return {
    key: recordId || "delivery-single",
    name: readConfigString(step.title, "交付节点"),
    status,
    meta,
    kind,
    content: kind === "direct" ? content : undefined,
    recordId: recordId || undefined,
    fileName: fileName || undefined,
    sizeBytes: readConfigString(resultRecord.sizeBytes) || undefined,
    downloadUrl: readConfigString(resultRecord.downloadUrl) || undefined,
    deliveryType: adapter || undefined,
  };
}

/** 从交付节点输出快照解析各交付项的可展示内容。 */
export function resolveDeliveryItems(deliveryStep?: RuntimePreviewStep): RuntimeDeliveryItem[] {
  if (!deliveryStep) {
    return [];
  }

  const records = readOutputValue(deliveryStep, "deliveryRecords");
  if (Array.isArray(records) && records.length > 0) {
    return records
      .filter(isRecord)
      .map((record, index) => mapDeliveryRecordToItem(record, index));
  }

  const single = mapSingleDeliveryOutputs(deliveryStep);
  return single ? [single] : [];
}

/**
 * 兼容旧调用：仅返回首个直接交付项的正文。
 * 不再回退到未渲染的配置模板，避免展示 {{variable}} 占位符。
 */
export function resolveDeliveryDisplayContent(deliveryStep?: RuntimePreviewStep): string {
  const items = resolveDeliveryItems(deliveryStep);
  const directItem = items.find((item) => item.kind === "direct" && item.content);
  return directItem?.content ?? "";
}
