import { useState } from "react";
import { Search } from "lucide-react";
import type { TemplateFieldTarget } from "../../utils/templateFormatGroups";

export function TemplateFieldPicker({ targets, value, onChange, owners = {}, disabled = false }: {
  targets: TemplateFieldTarget[]; value: string[]; onChange: (value: string[]) => void; owners?: Record<string, string>; disabled?: boolean;
}) {
  const [search, setSearch] = useState("");
  const [sheet, setSheet] = useState("");
  const visible = targets.filter(target => (!sheet || target.sheets.includes(sheet)) && `${target.path} ${target.path.replace(".", "[].")} ${target.label} ${target.group} ${target.sheets.join(" ")}`.toLowerCase().includes(search.trim().toLowerCase()));
  const selected = new Set(value);
  const sheets = [...new Set(targets.flatMap(target => target.sheets))];
  const groups = [...new Set(visible.map(target => target.group))];
  const add = (items: TemplateFieldTarget[]) => onChange([...new Set([...value, ...items.map(item => item.path)])]);
  return <div className="template-field-picker">
    <div className="template-picker-toolbar"><div className="sys-field-input-wrap"><Search size={15} className="sys-field-prefix" /><input className="sys-field-input" aria-label="搜索应用字段" value={search} disabled={disabled} onChange={event => setSearch(event.target.value)} placeholder="搜索字段名称、标识或 Sheet" /></div><span>已选 {value.length} / {targets.length} 个字段</span></div>
    {sheets.length > 1 && <div className="template-picker-filters" aria-label="按 Sheet 筛选"><button type="button" className={`sys-btn sys-btn--sm ${!sheet ? "sys-btn--primary" : "sys-btn--default"}`} disabled={disabled} onClick={() => setSheet("")}>全部 Sheet</button>{sheets.map(name => <button key={name} type="button" disabled={disabled} className={`sys-btn sys-btn--sm ${sheet === name ? "sys-btn--primary" : "sys-btn--default"}`} onClick={() => setSheet(name)}>{name}</button>)}</div>}
    <div className="template-picker-toolbar"><span className="sys-field-hint">同一字段的所有填写位置一起生效</span><div className="flex flex-wrap gap-2"><button type="button" disabled={disabled || !visible.length} className="sys-btn sys-btn--default sys-btn--sm" onClick={() => add(visible)}>全选当前结果</button><button type="button" disabled={disabled || !value.length} className="sys-btn sys-btn--default sys-btn--sm" onClick={() => onChange([])}>清空选择</button></div></div>
    <div className="template-picker-list">{groups.map(group => <section key={group}><div className="template-picker-group"><strong>{group}</strong><button type="button" disabled={disabled} className="sys-btn sys-btn--default sys-btn--sm" onClick={() => add(visible.filter(item => item.group === group))}>全选本组</button></div>{visible.filter(target => target.group === group).map(target => <label className="template-picker-item" key={target.path}><input type="checkbox" disabled={disabled} checked={selected.has(target.path)} onChange={event => onChange(event.target.checked ? [...value, target.path] : value.filter(path => path !== target.path))} /><span><strong>{target.label}</strong><code>{target.path.replace(".", "[].")}</code><small>{target.sheets.join("、") || "正文"}{owners[target.path] ? ` · 当前方案：${owners[target.path]}` : ""}</small></span></label>)}</section>)}{!visible.length && <p className="sys-field-hint">没有匹配的字段，请调整搜索或 Sheet 筛选。</p>}</div>
  </div>;
}
