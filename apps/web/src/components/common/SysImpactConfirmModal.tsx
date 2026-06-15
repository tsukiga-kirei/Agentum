import { CircleAlert } from "lucide-react";

type SysImpactConfirmModalProps = {
  open: boolean;
  title: string;
  message: string;
  confirmLabel: string;
  cancelLabel?: string;
  confirmDanger?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
};

/** 系统统一样式确认弹窗，支持深色模式，用于变量影响等需要二次确认的场景。 */
export function SysImpactConfirmModal({
  open,
  title,
  message,
  confirmLabel,
  cancelLabel = "取消",
  confirmDanger = false,
  onConfirm,
  onCancel,
}: SysImpactConfirmModalProps) {
  if (!open) {
    return null;
  }

  const titleId = "sys-impact-confirm-title";

  return (
    <div className="sys-modal-mask agent-delete-confirm-mask" onClick={onCancel}>
      <div
        className="sys-modal agent-delete-confirm-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        onClick={(event) => event.stopPropagation()}
      >
        <div className="agent-delete-confirm-body">
          <div className="agent-delete-confirm-icon">
            <CircleAlert size={28} aria-hidden="true" />
          </div>
          <div className="agent-delete-confirm-content">
            <h2 id={titleId}>{title}</h2>
            <p className="whitespace-pre-wrap">{message}</p>
          </div>
        </div>
        <div className="agent-delete-confirm-footer">
          <button type="button" className="sys-btn sys-btn--default" onClick={onCancel}>
            {cancelLabel}
          </button>
          <button
            type="button"
            className={confirmDanger ? "sys-btn sys-btn--danger" : "sys-btn sys-btn--primary"}
            onClick={onConfirm}
          >
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
