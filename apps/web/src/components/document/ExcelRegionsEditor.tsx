import { TemplateInput, TemplateSelect } from "./TemplateEditors";
export type ExcelDataRegion = { startCell: string; sourceNodeId: string; sourcePath: string; header: boolean; columns: { field: string; label: string; type: string; format?: string }[] };
export function ExcelRegionsEditor({ value, sources = [], onChange }: { value: ExcelDataRegion[]; sources?: { value: string; label: string }[]; onChange: (value: ExcelDataRegion[]) => void }) {
  const patch = (index: number, change: Partial<ExcelDataRegion>) => onChange(value.map((r, i) => i === index ? { ...r, ...change } : r));
  return <section className="mt-4 grid gap-3"><strong>独立数据区域</strong><p className="sys-field-hint">每个区域独立向下填充，可在同一 Sheet 左右并列。数据集来源路径如 mcp_datasets.credit_details；区域不能覆盖正文内容。</p>
    {value.map((region, index) => <div key={index} className="grid gap-3 rounded-lg border border-[var(--color-border)] p-3">
      <div className="flex justify-between"><strong>区域 {index + 1}</strong><button type="button" className="sys-btn sys-btn--default" onClick={() => onChange(value.filter((_, i) => i !== index))}>删除区域</button></div>
      <TemplateInput label="开始单元格" value={region.startCell} onChange={startCell => patch(index, { startCell: startCell.toUpperCase() })} />
      <TemplateSelect value={region.sourceNodeId} options={[{ value: "", label: "从交付变量读取" }, ...sources]} onChange={sourceNodeId => patch(index, { sourceNodeId })} placeholder="选择上游来源步骤" />
      <TemplateInput label="来源字段路径" value={region.sourcePath} onChange={sourcePath => patch(index, { sourcePath })} />
      <label className="flex gap-2"><input type="checkbox" checked={region.header} onChange={e => patch(index, { header: e.target.checked })} />在开始位置生成表头，数据从下一行写入</label>
      {region.columns.map((column, ci) => <div key={ci} className="grid gap-2 rounded border border-[var(--color-border)] p-2">
        <TemplateInput label="来源字段" value={column.field} onChange={field => patch(index, { columns: region.columns.map((c, i) => i === ci ? { ...c, field } : c) })} />
        <TemplateInput label="表头" value={column.label} onChange={label => patch(index, { columns: region.columns.map((c, i) => i === ci ? { ...c, label } : c) })} />
        <TemplateSelect value={column.type} options={[{ value: "text", label: "文字" }, { value: "number", label: "数字" }, { value: "date", label: "日期" }, { value: "boolean", label: "是／否" }]} onChange={type => patch(index, { columns: region.columns.map((c, i) => i === ci ? { ...c, type } : c) })} />
        <button type="button" className="sys-btn sys-btn--default" onClick={() => patch(index, { columns: region.columns.filter((_, i) => i !== ci) })}>删除列</button>
      </div>)}
      <button type="button" className="sys-btn sys-btn--default" onClick={() => patch(index, { columns: [...region.columns, { field: "", label: "", type: "text" }] })}>添加数据列</button>
    </div>)}
    <button type="button" className="sys-btn sys-btn--default" onClick={() => onChange([...value, { startCell: "A1", sourceNodeId: "", sourcePath: "", header: true, columns: [] }])}>添加独立区域</button>
  </section>;
}
