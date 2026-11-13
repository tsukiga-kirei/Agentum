import { useState } from "react";
import { copyMcpResultPolicy, newMcpResultKey } from "../../utils/mcpResultPolicies";
import { TemplateInput, TemplateSelect } from "./TemplateEditors";

type Policy = Record<string, unknown>;
export function McpResultPolicyEditor({ value, tools = [], onChange, onDiscover }: { value: Record<string, Policy>; tools?: { name: string; description?: string }[]; onDiscover?: () => void; onChange: (value: Record<string, Policy>) => void }) {
  const policies = value;
  const [copyFrom, setCopyFrom] = useState("");
  const [copyTarget, setCopyTarget] = useState("");
  const [tool, setTool] = useState("");
  const update = (next: Record<string, Policy>) => onChange(next);
  const patch = (name: string, change: Policy) => update({ ...policies, [name]: { ...policies[name], ...change } });
  return <section className="grid gap-4"><div className="template-section-heading"><strong>工具结果处理</strong>{onDiscover && <button type="button" className="sys-btn sys-btn--default sys-btn--sm" onClick={onDiscover}>获取 / 刷新工具列表</button>}</div><p className="sys-field-hint">选择具体工具，为它配置结果处理。完整明细随本次流程保存，供交付读取；对话和模型只展示受限预览。分页工具可配置自动采集。</p>
    {Object.entries(policies).map(([name, p]) => <div key={name} className="grid gap-3 rounded-lg border border-[var(--color-border)] p-3">
      <div className="template-section-heading"><div><strong className="break-all">{name}</strong><p className="sys-field-hint">{tools.find(tool => tool.name === name)?.description || (tools.length ? "工具不在当前发现列表中，请确认服务是否已变更" : "已绑定工具；获取工具列表可查看说明")}</p></div><div className="flex flex-wrap gap-2"><button type="button" className="sys-btn sys-btn--default" onClick={() => { setCopyFrom(name); setCopyTarget(""); }}>复制规则</button><button type="button" className="sys-btn sys-btn--default" onClick={() => update(Object.fromEntries(Object.entries(policies).filter(([key]) => key !== name)))}>移除规则</button></div></div>
      {copyFrom === name && <div className="grid gap-3"><p className="sys-field-hint">选择尚未配置的目标工具，复制后可继续修改结果名称、字段和分页参数。原规则保留。</p><div className="flex flex-wrap gap-2"><TemplateSelect value={copyTarget || undefined} options={tools.filter(item => !(item.name in policies)).map(item => ({ value: item.name, label: item.name }))} placeholder="选择复制到的工具" onChange={setCopyTarget} /><button type="button" className="sys-btn sys-btn--primary" disabled={!copyTarget || copyTarget in policies} onClick={() => { update(copyMcpResultPolicy(policies, name, copyTarget)); setCopyFrom(""); setCopyTarget(""); }}>确认复制</button><button type="button" className="sys-btn sys-btn--default" onClick={() => setCopyFrom("")}>取消</button></div>{!tools.some(item => !(item.name in policies)) && <p className="sys-field-hint">暂无未配置工具，请先获取 / 刷新工具列表。</p>}</div>}
      <TemplateSelect value={String(p.mode ?? "direct")} options={[{ value: "direct", label: "完整返回给模型" }, { value: "dataset", label: "保存数据集，返回受限预览" }, { value: "auto", label: "小结果直接返回，大结果保存" }]} onChange={mode => patch(name, { mode })} />
      {p.mode !== "direct" && <>
        <TemplateInput label="结果名称（用于流程绑定）" value={String(p.datasetKey ?? name)} onChange={datasetKey => patch(name, { datasetKey })} />
        <TemplateSelect value={String(p.source ?? "structuredContent")} options={[{ value: "structuredContent", label: "MCP 结构化内容" }, { value: "response", label: "整个 MCP 响应对象" }, { value: "text_json", label: "唯一文本块中的 JSON" }]} onChange={source => patch(name, { source })} />
        <TemplateInput label="明细所在位置" value={String(p.rowsPath ?? "$.rows")} onChange={rowsPath => patch(name, { rowsPath })} /><p className="sys-field-hint">例如返回 {'{"data":{"rows":[...]}}'} 时填写 $.data.rows；直接返回数组时填写 $。路径相对于上方选定的结果来源。</p>
        <TemplateInput label="结果字段（选填，用于绑定时选择）" value={Array.isArray(p.columns) ? p.columns.join(", ") : ""} onChange={columns => patch(name, { columns: columns.split(/[,，]/).map(value => value.trim()).filter(Boolean) })} placeholder="例如 account_no, amount, start_date" />
        <TemplateSelect value={String(p.preview ?? "summary")} options={[{ value: "summary", label: "仅引用、条数和字段" }, { value: "head", label: "前 N 条" }, { value: "tail", label: "后 N 条" }, { value: "head_tail", label: "首尾各 N 条" }]} onChange={preview => patch(name, { preview })} />
        <TemplateInput label="预览条数（1～100）" value={String(p.previewRows ?? 3)} onChange={previewRows => patch(name, { previewRows: Number(previewRows) })} />
        <div className="sys-field"><label className="sys-field-label">模型可见字段（逗号分隔，留空允许全部字段）</label><input className="sys-field-input" defaultValue={Array.isArray(p.fields) ? p.fields.join(", ") : ""} onBlur={e => { const fields = e.target.value; const next = { ...p }; if (fields.trim()) next.fields = fields.split(",").map(v => v.trim()).filter(Boolean); else delete next.fields; update({ ...policies, [name]: next }); }} /></div>
        <label className="flex gap-2"><input type="checkbox" checked={(p.pagination as Policy | undefined)?.type === "page"} onChange={e => patch(name, { hasMorePath: p.hasMorePath || "$.page.hasMore", pagination: e.target.checked ? { type: "page", pageParam: "page", pageSizeParam: "pageSize", pageSize: 500, maxPages: 200 } : { type: "none" } })} />自动采集所有页</label>
        {(p.pagination as Policy | undefined)?.type === "page" && <>
          {[["pageParam", "页码参数", "page"], ["pageSizeParam", "每页条数参数", "pageSize"], ["pageSize", "每页条数", "500"]].map(([key, label, fallback]) => <TemplateInput key={key} label={label} value={String((p.pagination as Policy)[key] ?? fallback)} onChange={v => patch(name, { pagination: { ...(p.pagination as Policy), [key]: key === "pageSize" ? Number(v) : v } })} />)}
          <TemplateInput label="是否有下一页的路径（布尔值）" value={String(p.hasMorePath ?? "")} onChange={hasMorePath => patch(name, { hasMorePath })} />
          <TemplateInput label="总条数路径（可选）" value={String(p.totalPath ?? "")} onChange={v => { const next = { ...p }; if (v.trim()) next.totalPath = v; else delete next.totalPath; update({ ...policies, [name]: next }); }} />
        </>}
      </>}
    </div>)}
    <div className="flex gap-2"><TemplateSelect value={tool || undefined} options={tools.filter(item => !(item.name in policies)).map(item => ({ value: item.name, label: item.name }))} placeholder={tools.length ? "选择要配置的工具" : "请先获取工具列表"} disabled={!tools.length} onChange={setTool} /><button type="button" className="sys-btn sys-btn--default" disabled={!tool || !tools.some(item => item.name === tool) || tool in policies} onClick={() => { update({ ...policies, [tool]: { mode: "direct", datasetKey: newMcpResultKey(tool) } }); setTool(""); }}>添加规则</button></div>
  </section>;
}
