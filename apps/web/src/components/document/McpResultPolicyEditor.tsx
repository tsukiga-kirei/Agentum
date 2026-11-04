import { useState } from "react";
import { TemplateInput, TemplateSelect } from "./TemplateEditors";

type Policy = Record<string, unknown>;
export function McpResultPolicyEditor({ value, tools = [], onChange }: { value: Record<string, Policy>; tools?: string[]; onChange: (value: Record<string, Policy>) => void }) {
  const [policies, setPolicies] = useState(value);
  const [tool, setTool] = useState("");
  const update = (next: Record<string, Policy>) => { setPolicies(next); onChange(next); };
  const patch = (name: string, change: Policy) => update({ ...policies, [name]: { ...policies[name], ...change } });
  return <section className="grid gap-4"><strong>工具结果处理</strong><p className="sys-field-hint">按远端工具名称配置。未配置的工具直接返回；数据集可用于 Excel，模型按需查看。配置分页时由系统自动采集。</p>
    {Object.entries(policies).map(([name, p]) => <div key={name} className="grid gap-3 rounded-lg border border-[var(--color-border)] p-3">
      <div className="flex justify-between"><strong>{name}</strong><button type="button" className="sys-btn sys-btn--default" onClick={() => update(Object.fromEntries(Object.entries(policies).filter(([key]) => key !== name)))}>移除规则</button></div>
      <TemplateSelect value={String(p.mode ?? "direct")} options={[{ value: "direct", label: "完整返回给模型" }, { value: "dataset", label: "保存数据集，返回受限预览" }, { value: "auto", label: "小结果直接返回，大结果保存" }]} onChange={mode => patch(name, { mode })} />
      {p.mode !== "direct" && <>
        <TemplateInput label="数据集标识" value={String(p.datasetKey ?? name)} onChange={datasetKey => patch(name, { datasetKey })} />
        <TemplateSelect value={String(p.source ?? "structuredContent")} options={[{ value: "structuredContent", label: "MCP 结构化内容" }, { value: "response", label: "整个 MCP 响应对象" }, { value: "text_json", label: "唯一文本块中的 JSON" }]} onChange={source => patch(name, { source })} />
        <TemplateInput label="明细数组路径，例如 $.data.rows" value={String(p.rowsPath ?? "$.rows")} onChange={rowsPath => patch(name, { rowsPath })} />
        <TemplateSelect value={String(p.preview ?? "summary")} options={[{ value: "summary", label: "仅引用、条数和字段" }, { value: "head", label: "前 N 条" }, { value: "tail", label: "后 N 条" }, { value: "head_tail", label: "首尾各 N 条" }]} onChange={preview => patch(name, { preview })} />
        <TemplateInput label="预览条数（1～100）" value={String(p.previewRows ?? 3)} onChange={previewRows => patch(name, { previewRows: Number(previewRows) })} />
        <div className="sys-field"><label className="sys-field-label">模型可见字段（逗号分隔，留空允许全部字段）</label><input className="sys-field-input" defaultValue={Array.isArray(p.fields) ? p.fields.join(", ") : ""} onBlur={e => { const fields = e.target.value; const next = { ...p }; if (fields.trim()) next.fields = fields.split(",").map(v => v.trim()).filter(Boolean); else delete next.fields; update({ ...policies, [name]: next }); }} /></div>
        <TemplateInput label="保留天数（1～365）" value={String(p.retentionDays ?? 7)} onChange={retentionDays => patch(name, { retentionDays: Number(retentionDays) })} />
        <label className="flex gap-2"><input type="checkbox" checked={(p.pagination as Policy | undefined)?.type === "page"} onChange={e => patch(name, { hasMorePath: p.hasMorePath || "$.page.hasMore", pagination: e.target.checked ? { type: "page", pageParam: "page", pageSizeParam: "pageSize", pageSize: 500, maxPages: 200 } : { type: "none" } })} />自动采集所有页</label>
        {(p.pagination as Policy | undefined)?.type === "page" && <>
          {[["pageParam", "页码参数", "page"], ["pageSizeParam", "每页条数参数", "pageSize"], ["pageSize", "每页条数", "500"]].map(([key, label, fallback]) => <TemplateInput key={key} label={label} value={String((p.pagination as Policy)[key] ?? fallback)} onChange={v => patch(name, { pagination: { ...(p.pagination as Policy), [key]: key === "pageSize" ? Number(v) : v } })} />)}
          <TemplateInput label="是否有下一页的路径（布尔值）" value={String(p.hasMorePath ?? "")} onChange={hasMorePath => patch(name, { hasMorePath })} />
          <TemplateInput label="总条数路径（可选）" value={String(p.totalPath ?? "")} onChange={v => { const next = { ...p }; if (v.trim()) next.totalPath = v; else delete next.totalPath; update({ ...policies, [name]: next }); }} />
        </>}
      </>}
    </div>)}
    <datalist id="mcp-result-policy-tools">{tools.map(name => <option key={name} value={name} />)}</datalist>
    <div className="flex gap-2"><input aria-label="远端工具名称" list="mcp-result-policy-tools" className="sys-field-input" value={tool} placeholder="输入远端工具名称" onChange={e => setTool(e.target.value)} /><button type="button" className="sys-btn sys-btn--default" disabled={!tool.trim() || tool.trim() in policies} onClick={() => { update({ ...policies, [tool.trim()]: { mode: "direct" } }); setTool(""); }}>添加规则</button></div>
  </section>;
}
