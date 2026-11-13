import { useState } from "react";
import { Plus, Trash2, WandSparkles } from "lucide-react";
import type { TemplateFieldPreset, TemplateFormatGroup, TemplateSpec } from "../../types/delivery-template";
import { assignTemplateFormatGroup, recommendedTemplateFields, templateFieldPresets, templateFieldTargets } from "../../utils/templateFormatGroups";
import { TemplateInput, TemplateSelect, TemplateStyleEditor } from "./TemplateEditors";
import { TemplateFieldPicker } from "./TemplateFieldPicker";

export function TemplateFormatGroupsEditor({ spec, kind, disabled, onChange }: {
  spec: TemplateSpec; kind: "word" | "excel"; disabled: boolean; onChange: (spec: TemplateSpec) => void;
}) {
  const [preset, setPreset] = useState<TemplateFieldPreset>("amount");
  const groups = spec.formatGroups ?? [];
  const targets = templateFieldTargets(spec.fields, spec.locations);
  const owners = Object.fromEntries(groups.flatMap(group => group.fields.map(field => [field, group.name])));
  const update = (group: TemplateFormatGroup, patch: Partial<TemplateFormatGroup>) => onChange(assignTemplateFormatGroup(spec, { ...group, ...patch }));
  return <section className="template-format-groups">
    <div className="template-format-intro"><strong>先设格式，再选择应用字段</strong><p className="sys-field-hint">一个方案可同时应用到多个 Sheet。同一字段只归属一个方案；加入新方案时会移出原方案。金额、汇率和日期方案会同时更新字段类型。</p></div>
    <div className="template-format-add"><TemplateSelect value={preset} options={templateFieldPresets.map(item => ({ value: item.value, label: `${item.label} · ${item.hint}` }))} disabled={disabled} onChange={setPreset} /><button type="button" disabled={disabled || groups.length >= 100} className="sys-btn sys-btn--primary" onClick={() => {
      const definition = templateFieldPresets.find(item => item.value === preset)!;
      onChange({ ...spec, formatGroups: [...groups, { id: crypto.randomUUID(), name: definition.label, preset, fields: [], style: { ...definition.style } }] });
    }}><Plus size={15} />新增格式方案</button></div>
    {!groups.length && <p className="sys-field-hint">暂无格式方案。可先添加“金额”，再点击“按名称推荐”批量选择余额、金额等字段。</p>}
    {groups.map(group => <details className="template-field-card template-format-group" key={group.id} open><summary className="template-section-heading"><strong>{group.name || "未命名方案"}</strong><span className="template-definition-meta">{templateFieldPresets.find(item => item.value === group.preset)?.label} · {group.fields.length} 个字段</span></summary><div className="template-definition-body">
      <div className="template-form-grid"><TemplateInput label="方案名称" value={group.name} disabled={disabled} onChange={name => update(group, { name })} /><div className="sys-field"><label className="sys-field-label">格式预设</label><TemplateSelect disabled={disabled} value={group.preset} options={templateFieldPresets.map(item => ({ value: item.value, label: `${item.label} · ${item.hint}` }))} onChange={next => {
        const definition = templateFieldPresets.find(item => item.value === next)!;
        const old = templateFieldPresets.find(item => item.value === group.preset);
        update(group, { preset: next, style: { ...definition.style }, name: group.name === old?.label ? definition.label : group.name });
      }} /></div></div>
      <div className="template-section-heading"><strong>应用字段 · {group.fields.length} 项</strong><div className="flex flex-wrap gap-2"><button type="button" disabled={disabled || group.preset === "custom"} className="sys-btn sys-btn--default sys-btn--sm" onClick={() => update(group, { fields: [...new Set([...group.fields, ...recommendedTemplateFields(targets, group.preset)])] })}><WandSparkles size={14} />按名称推荐</button><button type="button" disabled={disabled} className="sys-btn sys-btn--default sys-btn--sm" onClick={() => onChange({ ...spec, formatGroups: groups.filter(item => item.id !== group.id) })}><Trash2 size={14} />删除方案</button></div></div>
      <p className="sys-field-hint template-format-selected">{group.fields.length ? group.fields.map(path => path.replace(".", "[].")).join("、") : "尚未选择字段，此方案暂不影响任何位置。"}</p>
      <TemplateFieldPicker targets={targets} value={group.fields} disabled={disabled} owners={owners} onChange={fields => update(group, { fields })} />
      <details className="template-column-details"><summary>调整方案样式</summary><fieldset className="template-editor-fieldset" disabled={disabled}><TemplateStyleEditor kind={kind} showPresets={false} value={group.style} onChange={style => update(group, { style })} /></fieldset></details>
    </div></details>)}
  </section>;
}
