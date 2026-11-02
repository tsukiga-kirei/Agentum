import type { AttachmentRecognitionCapabilities } from "../types/system";

export function normalizeAttachmentExtensions(values: string[] | undefined): string[] {
  return Array.from(new Set((values ?? [])
    .map((value) => value.trim().toLowerCase().replace(/^\./, ""))
    .filter(Boolean)));
}

/**
 * 识别启用时，字段白名单只能收窄系统当前识别能力；识别关闭时保留仅保存原件的既有行为。
 */
export function getEffectiveAttachmentExtensions(
  configuredExtensions: string[] | undefined,
  capabilities: AttachmentRecognitionCapabilities,
): string[] {
  const configured = normalizeAttachmentExtensions(configuredExtensions);
  const blocked = new Set(normalizeAttachmentExtensions(capabilities.blockedExtensions));
  if (!capabilities.recognitionEnabled) {
    return configured.filter((extension) => !blocked.has(extension));
  }
  const supported = new Set(normalizeAttachmentExtensions(capabilities.supportedExtensions));
  return configured.filter((extension) => supported.has(extension) && !blocked.has(extension));
}

export function getUnsupportedAttachmentExtensions(
  configuredExtensions: string[] | undefined,
  capabilities: AttachmentRecognitionCapabilities,
): string[] {
  const blocked = new Set(normalizeAttachmentExtensions(capabilities.blockedExtensions));
  const supported = new Set(normalizeAttachmentExtensions(capabilities.supportedExtensions));
  return normalizeAttachmentExtensions(configuredExtensions).filter((extension) => (
    blocked.has(extension) || (capabilities.recognitionEnabled && !supported.has(extension))
  ));
}

export function getAttachmentRecognitionEngineLabel(capabilities: AttachmentRecognitionCapabilities): string {
  if (capabilities.recognitionEngine === "hybrid") return "混合识别";
  return capabilities.recognitionEngine === "mineru" ? "复杂识别" : "简单识别";
}

/** 展示用户可理解的识别路径，后台保留稳定原因码供排障和审计。 */
export function getAttachmentParseSummary(parserType: string | null, fallbackReason: string | null): string {
  if (fallbackReason === "DOCX_AS_DOC") return "已使用旧版 Word 兼容识别";
  if (parserType === "mineru" && fallbackReason === "LOCAL_TEXT_EMPTY") return "未提取到本地文字，已使用复杂识别";
  if (parserType === "mineru" && fallbackReason === "LOCAL_PARSE_FAILED") return "本地提取失败，已使用复杂识别";
  if (parserType === "mineru") return "已使用复杂识别";
  if (parserType?.startsWith("local-")) return "已使用本地识别";
  return "";
}
