import { type ReactNode, useEffect, useRef } from "react";
import { createPortal } from "react-dom";
import { pushModalCloseHandler } from "./sysModalStack";

type SysModalMaskProps = {
  children: ReactNode;
  onClose: () => void;
  className?: string;
  closeOnEscape?: boolean;
  closeOnBackdrop?: boolean;
};

/** 系统弹窗外层遮罩：仅在遮罩上按下鼠标时关闭，避免输入框拖选误关；支持 Esc 按层级逐级关闭；使用 Portal 挂载到 body 保证层叠层级与居中。 */
export function SysModalMask({
  children,
  onClose,
  className,
  closeOnEscape = true,
  closeOnBackdrop = true,
}: SysModalMaskProps) {
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  useEffect(() => {
    if (!closeOnEscape) {
      return undefined;
    }

    return pushModalCloseHandler(() => onCloseRef.current());
  }, [closeOnEscape]);

  function handleBackdropMouseDown(event: React.MouseEvent<HTMLDivElement>) {
    if (!closeOnBackdrop) {
      return;
    }

    // 仅在遮罩自身按下时关闭，避免从输入框拖选到遮罩外时误关弹窗。
    if (event.target === event.currentTarget) {
      onClose();
    }
  }

  const maskClassName = className ? `sys-modal-mask ${className}` : "sys-modal-mask";

  const content = (
    <div className={maskClassName} onMouseDown={handleBackdropMouseDown}>
      {children}
    </div>
  );

  if (typeof document !== "undefined") {
    return createPortal(content, document.body);
  }

  return content;
}
