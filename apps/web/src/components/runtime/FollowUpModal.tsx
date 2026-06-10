import React, { useEffect, useState } from "react";
import { Send, X } from "lucide-react";

interface FollowUpModalProps {
  open: boolean;
  onClose: () => void;
  onSubmit: (message: string) => void;
}

/** 居中弹窗提交追问内容，续跑智能体对话。 */
export function FollowUpModal({
  open,
  onClose,
  onSubmit,
}: FollowUpModalProps) {
  const [draft, setDraft] = useState("");

  useEffect(() => {
    if (open) {
      setDraft("");
    }
  }, [open]);

  if (!open) {
    return null;
  }

  function handleSubmit() {
    onSubmit(draft);
  }

  return (
    <div className="sys-modal-mask" onClick={onClose}>
      <section
        className="sys-modal agent-follow-up-modal"
        aria-labelledby="follow-up-modal-title"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="sys-modal-header">
          <span id="follow-up-modal-title" className="sys-modal-title">追问智能体</span>
          <button type="button" className="sys-modal-close" onClick={onClose} aria-label="关闭">
            <X size={18} />
          </button>
        </div>

        <div className="sys-modal-body">
          <textarea
            className="sys-field-textarea agent-follow-up-textarea"
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            placeholder="输入追问内容…"
            autoFocus
          />
        </div>

        <div className="sys-modal-footer">
          <button type="button" className="sys-btn sys-btn--default" onClick={onClose}>
            <X size={14} aria-hidden="true" />
            取消
          </button>
          <button type="button" className="sys-btn sys-btn--primary" onClick={handleSubmit}>
            <Send size={14} aria-hidden="true" />
            发送
          </button>
        </div>
      </section>
    </div>
  );
}
