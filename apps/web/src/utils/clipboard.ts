/**
 * 将文本写入系统剪贴板；在非安全上下文中回退到浏览器兼容复制方案。
 */
export async function copyTextToClipboard(text: string): Promise<void> {
  if (typeof navigator !== "undefined" && navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text);
      return;
    } catch {
      // 用户可能拒绝剪贴板权限或浏览器策略禁止写入，继续尝试兼容路径。
    }
  }

  if (typeof document === "undefined" || !document.body) {
    throw new Error("Clipboard is unavailable");
  }

  const textArea = document.createElement("textarea");
  textArea.value = text;
  textArea.readOnly = true;
  textArea.setAttribute("aria-hidden", "true");
  textArea.style.position = "fixed";
  textArea.style.top = "0";
  textArea.style.left = "-9999px";
  textArea.style.opacity = "0";
  textArea.style.pointerEvents = "none";

  const activeElement = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  // Ant Design React 的抽屉和弹窗会把焦点锁在 role=dialog 容器内。
  // 临时文本框若直接放到 body，焦点会在选中前被抢回，execCommand 可能返回成功但没有复制目标文本。
  const copyContainer = activeElement?.closest<HTMLElement>('[role="dialog"]') ?? document.body;

  copyContainer.appendChild(textArea);
  textArea.focus({ preventScroll: true });
  textArea.select();
  textArea.setSelectionRange(0, textArea.value.length);

  try {
    if (!document.execCommand("copy")) {
      throw new Error("Clipboard copy command failed");
    }
  } finally {
    textArea.remove();
    activeElement?.focus({ preventScroll: true });
  }
}
