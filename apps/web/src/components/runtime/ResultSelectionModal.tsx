import { useState } from "react";
import { Check, FileText } from "lucide-react";
import { SysModalMask } from "../common/SysModalMask";

export const CONSOLIDATE_RESULT_PROMPT = "请基于本节点原始任务、当前正式结果和全部追问对话，整理一份完整可供下游使用的结果。保留仍然有效的数据和章节，合并已明确的修改，不要只输出修改说明或新增片段。";

export type ResultSelectionGroup = {
  agentIndex?: number;
  title: string;
  replies: Array<{ index: number; content: string }>;
};

/** 追问后推进前明确选择输出，避免最后一条解释性回复被默认交给下游。 */
export function ResultSelectionModal({ groups, onClose, onConfirm, onConsolidate }: {
  groups: ResultSelectionGroup[];
  onClose: () => void;
  onConfirm: (choices: Array<{ agentIndex?: number; messageIndex: number }>) => Promise<void>;
  onConsolidate: (agentIndex?: number) => void;
}) {
  const [choices, setChoices] = useState<Record<string, number>>({});
  const [busy, setBusy] = useState(false);
  const ready = groups.every(group => choices[String(group.agentIndex ?? "single")] !== undefined);

  async function confirm() {
    setBusy(true);
    try {
      await onConfirm(groups.map(group => ({ agentIndex: group.agentIndex, messageIndex: choices[String(group.agentIndex ?? "single")] })));
    } finally { setBusy(false); }
  }

  return (
    <SysModalMask onClose={() => { if (!busy) onClose(); }}>
      <section role="dialog" aria-modal="true" aria-labelledby="result-choice-title" className="sys-modal workbench-result-choice-modal">
        <div className="sys-modal-header"><h3 id="result-choice-title" className="sys-modal-title">选择本步结果</h3></div>
        <div className="sys-modal-body">
          <p className="text-sm text-[var(--color-text-secondary)]">追问后，请明确交给下一步的内容。可以选择任意一条回答，或先整理完整结果再查看。</p>
          <div className="workbench-result-choice-groups">
            {groups.map(group => {
              const key = String(group.agentIndex ?? "single");
              return <fieldset key={key}>
                <legend className="mb-3 text-sm font-semibold text-[var(--color-text-primary)]">{group.title}</legend>
                <div className="space-y-2">
                  {group.replies.map((reply, index) => <label key={reply.index} className="workbench-result-choice-option" data-selected={choices[key] === reply.index}>
                    <input type="radio" name={`result-${key}`} checked={choices[key] === reply.index} onChange={() => setChoices(old => ({ ...old, [key]: reply.index }))} />
                    <span><strong>第 {index + 1} 条回答</strong><span>{reply.content.length > 180 ? reply.content.slice(0, 180) + "…" : reply.content}</span></span>
                  </label>)}
                </div>
                <button type="button" className="sys-btn sys-btn--default mt-3" disabled={busy} onClick={() => onConsolidate(group.agentIndex)}><FileText size={14} />整理完整结果</button>
              </fieldset>;
            })}
          </div>
        </div>
        <div className="sys-modal-footer">
          <button type="button" className="sys-btn sys-btn--default" disabled={busy} onClick={onClose}>返回查看</button>
          <button type="button" className="sys-btn sys-btn--primary" disabled={!ready || busy} onClick={() => void confirm()}><Check size={14} />{busy ? "正在提交…" : "使用所选回答并继续"}</button>
        </div>
      </section>
    </SysModalMask>
  );
}
