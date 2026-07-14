/**
 * 复制文本到系统剪贴板。
 *
 * 局域网测试环境经常通过 HTTP IP 访问，不属于浏览器安全上下文，无法使用异步 Clipboard API。
 * 此时退回到浏览器仍广泛支持的选区复制能力；正式 HTTPS 环境继续优先使用权限边界更清晰的新 API。
 */
export async function copyTextToClipboard(text: string): Promise<void> {
  if (window.isSecureContext && navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text);
      return;
    } catch {
      // 用户可能拒绝剪贴板权限或浏览器策略禁止写入，继续尝试兼容路径。
    }
  }

  if (!copyTextWithSelection(text)) {
    throw new Error("当前浏览器不允许写入剪贴板");
  }
}

function copyTextWithSelection(text: string): boolean {
  if (!document.body || typeof document.execCommand !== "function") {
    return false;
  }

  const activeElement = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  const selection = document.getSelection();
  const previousRanges = selection
    ? Array.from({ length: selection.rangeCount }, (_, index) => selection.getRangeAt(index).cloneRange())
    : [];
  const textArea = document.createElement("textarea");

  textArea.value = text;
  textArea.readOnly = true;
  textArea.setAttribute("aria-hidden", "true");
  textArea.style.position = "fixed";
  textArea.style.top = "0";
  textArea.style.left = "-9999px";
  textArea.style.opacity = "0";
  textArea.style.pointerEvents = "none";
  document.body.appendChild(textArea);

  try {
    textArea.focus({ preventScroll: true });
    textArea.select();
    textArea.setSelectionRange(0, text.length);
    return document.execCommand("copy");
  } finally {
    textArea.remove();
    activeElement?.focus({ preventScroll: true });
    if (selection) {
      selection.removeAllRanges();
      previousRanges.forEach((range) => selection.addRange(range));
    }
  }
}
