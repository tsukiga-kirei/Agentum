/**
 * 弹窗全局层级栈：
 * 当页面同时存在多层弹窗（如抽屉中打开新建弹窗，再打开对象选择器或二次确认框）时，
 * 按 Esc 键只应关闭最顶层的当前弹窗，避免同时连带关闭底层弹窗导致表单输入丢失。
 */
export type ModalCloseHandler = () => void;

const modalCloseStack: ModalCloseHandler[] = [];

export function pushModalCloseHandler(handler: ModalCloseHandler): () => void {
  modalCloseStack.push(handler);
  return () => {
    const index = modalCloseStack.lastIndexOf(handler);
    if (index !== -1) {
      modalCloseStack.splice(index, 1);
    }
  };
}

export function handleTopModalEscape(): boolean {
  if (modalCloseStack.length === 0) {
    return false;
  }
  const topClose = modalCloseStack[modalCloseStack.length - 1];
  topClose();
  return true;
}

export function getModalStackDepth(): number {
  return modalCloseStack.length;
}

export function clearModalStackForTest(): void {
  modalCloseStack.length = 0;
}

if (typeof window !== "undefined") {
  window.addEventListener("keydown", (event: KeyboardEvent) => {
    if (event.key === "Escape") {
      const handled = handleTopModalEscape();
      if (handled) {
        event.stopPropagation();
      }
    }
  });
}
