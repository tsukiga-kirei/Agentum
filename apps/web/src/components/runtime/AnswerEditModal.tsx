import React, { useEffect, useState } from "react";
import { Save, X } from "lucide-react";
import { MarkdownRenderer } from "./MarkdownRenderer";

interface AnswerEditModalProps {
  open: boolean;
  initialValue: string;
  onClose: () => void;
  onSave: (value: string) => void;
}

/** 居中弹窗编辑最终答案，左侧 Markdown 编辑、右侧实时预览。 */
export function AnswerEditModal({
  open,
  initialValue,
  onClose,
  onSave,
}: AnswerEditModalProps) {
  const [draft, setDraft] = useState(initialValue);

  useEffect(() => {
    if (open) {
      setDraft(initialValue);
    }
  }, [open, initialValue]);

  if (!open) {
    return null;
  }

  return (
    <div className="sys-modal-mask" onClick={onClose}>
      <section
        className="sys-modal agent-answer-edit-modal"
        aria-labelledby="answer-edit-modal-title"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="sys-modal-header">
          <span id="answer-edit-modal-title" className="sys-modal-title">修改最终答案</span>
          <button type="button" className="sys-modal-close" onClick={onClose} aria-label="关闭">
            <X size={18} />
          </button>
        </div>

        <div className="sys-modal-body agent-answer-edit-modal-body">
          <div className="agent-answer-edit-modal-grid">
            <label className="sys-field min-h-0">
              <span className="sys-field-label">编辑</span>
              <textarea
                className="sys-field-textarea agent-answer-edit-textarea"
                value={draft}
                onChange={(event) => setDraft(event.target.value)}
                placeholder="编辑最终答案…"
              />
            </label>
            <div className="agent-answer-edit-preview min-h-0">
              <div className="sys-field-label">预览</div>
              <div className="agent-answer-edit-preview-body">
                <MarkdownRenderer content={draft || "暂无内容"} compact />
              </div>
            </div>
          </div>
        </div>

        <div className="sys-modal-footer">
          <button type="button" className="sys-btn sys-btn--default" onClick={onClose}>
            <X size={14} aria-hidden="true" />
            取消
          </button>
          <button type="button" className="sys-btn sys-btn--primary" onClick={() => onSave(draft)}>
            <Save size={14} aria-hidden="true" />
            保存
          </button>
        </div>
      </section>
    </div>
  );
}
