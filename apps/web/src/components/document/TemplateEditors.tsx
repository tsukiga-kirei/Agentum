import { useId, useRef, useState } from "react";
import { Input, Select, Segmented } from "antd";
import { CalendarDays, FileText, FileUp, Hash, Plus, Trash2, Type, Settings2, Tags } from "lucide-react";
import { newTemplateField, templateFieldTypes } from "../../types/delivery-template";
import type {
  StructuredOutputConfig,
  TemplateField,
  TemplateStyle,
  TemplateSpec,
} from "../../types/delivery-template";
import "./TemplateEditors.css";

export function TemplateTabs({ value, options, onChange }: { value: string | number; options: { value: string | number; label: string }[]; onChange: (value: string | number) => void }) {
  return <div className="system-mgmt-module-switch account-drawer-module-switch"><div className="system-mgmt-segmented-scroll"><Segmented aria-label="配置页签" className="login-portal-segmented login-portal-segmented--business system-mgmt-segmented" value={value} options={options} onChange={onChange} /></div></div>;
}

export function TemplateFieldLocations({ field, column, spec, onChange }: { field: TemplateField; column?: string; spec: TemplateSpec; onChange: (spec: TemplateSpec) => void }) {
  const regions = (spec.excelRegions ?? []).filter(region => region.field === field.key && (!column || region.column === column));
  if (!regions.length) return null;
  const directions = [{ value: "vertical", label: "纵向 · 向下" }, { value: "horizontal", label: "横向 · 向右" }];
  const growthOptions = [{ value: "shift", label: "推移后续内容，自动留出空间" }, { value: "fixed", label: "仅使用已有空白，冲突时报错" }];
  const patch = (region: typeof regions[number], change: Partial<typeof region>) => onChange({ ...spec, excelRegions: spec.excelRegions?.map(item => item === region ? { ...item, ...change } : item) });
  return <section className="template-location-list">{!column && <>
    <div className="template-section-heading"><strong>填写位置与扩展方向</strong><TemplateSelect value={regions.every(region => region.direction === regions[0].direction) ? regions[0].direction ?? "vertical" : "mixed"} options={[...directions, { value: "mixed", label: "各位置分别设置" }]} onChange={direction => { if (direction !== "mixed") onChange({ ...spec, excelRegions: spec.excelRegions?.map(region => region.field === field.key ? { ...region, direction: direction as "vertical" | "horizontal" } : region) }); }} /></div>
    <p className="sys-field-hint">已识别 {regions.length} 个位置。各位置按同一数组索引填写，空值留空；自动推移会插入整行或整列，保持后续内容整体对齐。</p>
  </>}
    {column && regions.map(region => <div className="template-location-entry" key={`${region.sheet}:${region.startCell}:${region.column}`}>
      <div className="template-location-row"><div><strong>{field.columns.find(item => item.key === region.column)?.label || region.column}</strong><span>{region.sheet} · {region.startCell}</span></div><TemplateSelect value={region.direction ?? "vertical"} options={directions} onChange={direction => patch(region, { direction: direction as "vertical" | "horizontal" })} /></div>
      <div className="sys-field"><label className="sys-field-label">扩展空间</label><TemplateSelect value={region.growth ?? "shift"} options={growthOptions} onChange={growth => patch(region, { growth: growth as "shift" | "fixed" })} /></div>
      <TemplateInput label="公式引用名称（工作簿范围）" value={region.rangeName ?? ""} onChange={rangeName => patch(region, { rangeName })} placeholder={`data_${field.key}_${region.column}`} />
      <p className="sys-field-hint">可在 Excel 原件中使用 <code>{`=SUM(${region.rangeName || `data_${field.key}_${region.column}`})`}</code>。生成时按实际条数更新此范围，包含中间空值、不包含表头和合计。名称修改后，原件中的公式也需一致。</p>
    </div>)}
  </section>;
}

export function TemplateSelect<T extends string | number>({
  value,
  options,
  onChange,
  placeholder,
  disabled,
}: {
  value?: T;
  options: { value: T; label: string }[];
  onChange: (value: T) => void;
  placeholder?: string;
  disabled?: boolean;
}) {
  return (
    <Select
      prefix={<Settings2 size={16} className="text-[var(--color-text-tertiary)]" />}
      className="agent-admin-select w-full"
      classNames={{ popup: { root: "agent-select-dropdown agent-admin-select-dropdown" } }}
      showSearch
      optionFilterProp="label"
      value={value}
      options={options}
      onChange={onChange}
      placeholder={placeholder}
      disabled={disabled}
    />
  );
}
export function TemplateInput({
  label,
  value,
  onChange,
  type = "text",
  disabled = false,
  placeholder,
}: {
  label: string;
  value: string | number;
  onChange: (value: string) => void;
  type?: string;
  disabled?: boolean;
  placeholder?: string;
}) {
  const id = useId();
  const Icon = type === "number" ? Hash : type === "date" ? CalendarDays : Type;
  return (
    <div className="sys-field">
      <label className="sys-field-label" htmlFor={id}>
        {label}
      </label>
      <div className="sys-field-input-wrap">
        <Icon size={16} className="sys-field-prefix" />
        <input
          id={id}
          className="sys-field-input"
          type={type}
          step={type === "number" ? "any" : undefined}
          value={value}
          onChange={(event) => onChange(event.target.value)}
          disabled={disabled}
          placeholder={placeholder}
        />
      </div>
    </div>
  );
}

export function TemplateFilePicker({ label, accept, fileName, disabled, onChange }: {
  label: string;
  accept: string;
  fileName?: string;
  disabled?: boolean;
  onChange: (file: File) => void;
}) {
  const id = useId();
  const input = useRef<HTMLInputElement>(null);
  const [selectedName, setSelectedName] = useState("");
  const displayName = selectedName || fileName;
  return <div className="template-file-picker">
    <input ref={input} id={id} type="file" accept={accept} aria-label={label} disabled={disabled} className="sr-only"
      onChange={(event) => {
        const file = event.target.files?.[0];
        if (file) { setSelectedName(file.name); onChange(file); }
        // 重新选择同一个修正后的文件也应触发处理，原生输入只负责选择，不承担文件状态展示。
        event.target.value = "";
      }} />
    <span className="template-file-picker-icon" aria-hidden="true"><FileUp size={22} /></span>
    <div className="template-file-picker-info"><strong title={displayName}>{displayName || "选择已设置字段标记的模板"}</strong><span>{accept.split(",").join(" / ")} · 最大 20 MB</span></div>
    <button type="button" className="sys-btn sys-btn--default sys-btn--sm" disabled={disabled} onClick={() => input.current?.click()}>{displayName ? "重新选择" : "选择文件"}</button>
  </div>;
}

export function TemplateStyleEditor({
  value,
  onChange,
  kind,
}: {
  value: TemplateStyle;
  onChange: (value: TemplateStyle) => void;
  kind: "word" | "excel";
}) {
  const update = (key: string, next: string | number | boolean | undefined) => {
    const result = { ...value };
    if (next === undefined || next === "") delete result[key];
    else result[key] = next;
    onChange(result);
  };
  const numeric =
    kind === "word"
      ? [
          ["fontSize", "字号（磅）"],
          ["firstLineChars", "首行缩进（字符）"],
          ["leftIndentCm", "左缩进（厘米）"],
          ["lineSpacing", "行距（倍数）"],
          ["spacingBefore", "段前（磅）"],
          ["spacingAfter", "段后（磅）"],
          ["cellPaddingPt", "表格内边距（磅）"],
        ]
      : [
          ["fontSize", "字号（磅）"],
          ["rowHeight", "行高（磅）"],
          ["columnWidth", "列宽（字符）"],
          ["indent", "单元格缩进"],
        ];
  const presets: Record<string, TemplateStyle> = { inherit: {}, body: kind === "word" ? { fontFamily: "宋体", fontSize: 12, firstLineChars: 2, lineSpacing: 1.5 } : { fontFamily: "宋体", fontSize: 12, wrapText: true }, risk: { color: "9C2F2F", backgroundColor: "FFF1F0", wrapText: true }, amount: { numberFormat: "#,##0.00", alignment: "right" } };
  const presetValue = Object.keys(presets).find(key => Object.keys(value).length === Object.keys(presets[key]).length && Object.entries(presets[key]).every(([name, setting]) => value[name] === setting)) ?? "custom";
  return (
    <div className="template-style-editor">
      <p className="sys-field-hint">
        空白表示沿用原有或上层格式；填写 0 可取消缩进或段间距。{kind === "excel" ? "行高和列宽会作用于整行、整列。" : "格式只作用于当前范围的填充内容。"}
      </p>
      <div className="template-form-grid">
        <div className="sys-field">
          <label className="sys-field-label">样式预设</label>
          <TemplateSelect
            value={presetValue}
            options={[
              { value: "custom", label: "当前设置 / 自定义" },
              { value: "inherit", label: "保持原有格式" },
              { value: "body", label: "报告正文" },
              { value: "risk", label: "风险说明" },
              { value: "amount", label: "金额明细" },
            ]}
            onChange={(preset) => { if (presets[preset]) onChange(presets[preset]); }}
          />
        </div>
        {kind === "excel" && <div className="sys-field"><label className="sys-field-label">明细行高</label><TemplateSelect value={String(value.rowHeightMode ?? (value.rowHeight !== undefined ? "fixed" : "auto"))} options={[{ value: "auto", label: "按填入内容适配" }, { value: "inherit", label: "沿用原模板行高" }, { value: "fixed", label: "固定行高（下方填写磅值）" }]} onChange={mode => update("rowHeightMode", mode)} /></div>}
        <TemplateInput
          label="字体"
          value={String(value.fontFamily ?? "")}
          onChange={(next) => update("fontFamily", next)}
          placeholder="沿用已有字体"
        />
        {numeric.filter(([key]) => key !== "rowHeight" || (value.rowHeightMode ?? (value.rowHeight !== undefined ? "fixed" : "auto")) === "fixed").map(([key, label]) => (
          <TemplateInput
            key={key}
            label={label}
            type="number"
            value={String(value[key] ?? "")}
            onChange={(next) => update(key, next === "" ? undefined : Number(next))}
            placeholder="继承"
          />
        ))}
        {[
          ["color", "文字颜色"],
          ["backgroundColor", "背景颜色"],
          ...(kind === "excel" ? [["numberFormat", "数字显示格式"]] : []),
        ].map(([key, label]) => (
          <TemplateInput
            key={key}
            label={label}
            value={String(value[key] ?? "")}
            onChange={(next) => update(key, next)}
            placeholder={key === "numberFormat" ? "例如 #,##0.00" : "例如 #1677FF"}
          />
        ))}
        <div className="sys-field">
          <label className="sys-field-label">对齐方式</label>
          <TemplateSelect
            value={String(value.alignment ?? "inherit")}
            options={[
              { value: "inherit", label: "继承" },
              { value: "left", label: "左对齐" },
              { value: "center", label: "居中" },
              { value: "right", label: "右对齐" },
              { value: "both", label: "两端对齐" },
            ]}
            onChange={(next) => update("alignment", next === "inherit" ? undefined : next)}
          />
        </div>
        <div className="sys-field">
          <label className="sys-field-label">单元格垂直对齐</label>
          <TemplateSelect
            value={String(value.verticalAlignment ?? "inherit")}
            options={[
              { value: "inherit", label: "继承" },
              { value: "top", label: "顶部" },
              { value: "center", label: "居中" },
              { value: "bottom", label: "底部" },
            ]}
            onChange={(next) => update("verticalAlignment", next === "inherit" ? undefined : next)}
          />
        </div>
        {(kind === "excel"
          ? [
              ["bold", "加粗"],
              ["italic", "斜体"],
              ["wrapText", "自动换行"],
              ["border", "单元格边框"],
            ]
          : [
              ["bold", "加粗"],
              ["italic", "斜体"],
              ["border", "表格单元格边框"],
            ]
        ).map(([key, label]) => (
          <div className="sys-field" key={key}>
            <label className="sys-field-label">{label}</label>
            <TemplateSelect
              value={value[key] === undefined ? "inherit" : String(value[key])}
              options={[
                { value: "inherit", label: "继承" },
                { value: "true", label: "开启" },
                { value: "false", label: "关闭" },
              ]}
              onChange={(next) => update(key, next === "inherit" ? undefined : next === "true")}
            />
          </div>
        ))}
      </div>
    </div>
  );
}

export function TemplateFieldsEditor({
  fields,
  onChange,
  lockedKeys = false,
  columns = false,
  renderLocations,
}: {
  fields: TemplateField[];
  onChange: (fields: TemplateField[]) => void;
  lockedKeys?: boolean;
  columns?: boolean;
  renderLocations?: (field: TemplateField, parent?: TemplateField) => React.ReactNode;
}) {
  const update = (index: number, patch: Partial<TemplateField>) =>
    onChange(fields.map((field, i) => (i === index ? { ...field, ...patch } : field)));
  return (
    <div className="template-field-list">
      {!columns && <p className="sys-field-hint">
        {lockedKeys
          ? "字段说明用于解释含义和取数口径，供绑定来源时参考；不会自动筛选、计算或修改上游数据。必填、类型、字数和条数由对应设置校验。"
          : "内容要求告诉智能体字段应包含什么、采用什么口径，会作为生成结构化结果时的说明。必填、类型、字数和条数由对应设置校验。"}
      </p>}
      {fields.map((field, index) => (
        <details className="template-field-card template-definition-card" key={`${index}`} open={!lockedKeys || index === 0}>
          <summary className="template-section-heading">
            <strong>
              <Hash size={15} />
              {field.label || field.key || `字段 ${index + 1}`}
            </strong>
            <span className="template-definition-meta">
              {lockedKeys && field.label && field.label !== field.key && <code>{field.key}</code>}
              <span>{templateFieldTypes.find(type => type.value === field.type)?.label}</span>
              <span className={field.required ? "template-required-tag" : ""}>{field.required ? "必填" : "选填"}</span>
            </span>
            {!lockedKeys && (
              <button
                className="sys-btn sys-btn--default"
                type="button"
                aria-label="删除字段"
                onClick={(event) => { event.preventDefault(); event.stopPropagation(); onChange(fields.filter((_, i) => i !== index)); }}
              >
                <Trash2 size={14} />
              </button>
            )}
          </summary>
          <div className="template-definition-body">
          <div className="template-form-grid">
            {!lockedKeys && <TemplateInput
              label="字段标识"
              value={field.key}
              onChange={(key) => update(index, { key })}
              disabled={lockedKeys}
              placeholder="例如 risk_summary"
            />}
            <div className={lockedKeys ? "template-full-row" : undefined}>
            <TemplateInput
              label="字段名称"
              value={field.label}
              onChange={(label) => update(index, { label })}
            />
            </div>
            <div className="sys-field">
              <label className="sys-field-label">内容类型</label>
              <TemplateSelect
                value={field.type}
                options={templateFieldTypes.filter((type) =>
                  lockedKeys
                    ? field.type === "table"
                      ? type.value === "table"
                      : type.value !== "table"
                    : columns
                      ? !["table", "paragraphs", "markdown"].includes(type.value)
                      : true,
                )}
                onChange={(type) =>
                  update(index, {
                    type: type as TemplateField["type"],
                    columns:
                      type === "table" && !field.columns.length
                        ? [newTemplateField("name")]
                        : field.columns,
                  })
                }
              />
            </div>
            <div className="sys-field">
              <label className="sys-field-label">是否必填</label>
              <TemplateSelect
                value={String(field.required)}
                options={[
                  { value: "true", label: "必填" },
                  { value: "false", label: "选填，缺失时留空" },
                ]}
                onChange={(next) => update(index, { required: next === "true" })}
              />
            </div>
            {["text", "markdown", "paragraphs"].includes(field.type) && (
              <TemplateInput
                label="最大字数"
                type="number"
                value={field.maxLength ?? ""}
                onChange={(next) => update(index, { maxLength: next ? Number(next) : undefined })}
              />
            )}
            {["table", "paragraphs"].includes(field.type) && (
              <TemplateInput
                label="最多条数 / 段数"
                type="number"
                value={field.maxItems ?? ""}
                onChange={(next) => update(index, { maxItems: next ? Number(next) : undefined })}
              />
            )}
          </div>
          <details className="template-column-details"><summary>补充说明与选项限制（选填）</summary>
          <TemplateInput
            label={lockedKeys ? "字段说明（选填）" : "内容要求（选填）"}
            value={field.description}
            onChange={(description) => update(index, { description })}
            placeholder="例如：本月末余额，单位为元，不包含已注销账户"
          />
          {field.type === "text" && (
            <div className="sys-field">
              <label className="sys-field-label">允许的选项</label>
              <Select
                prefix={<Tags size={16} className="text-[var(--color-text-tertiary)]" />}
                mode="tags"
                placeholder="输入选项后回车，留空不限制"
                className="agent-admin-select w-full"
                classNames={{
                  popup: { root: "agent-select-dropdown agent-admin-select-dropdown" },
                }}
                value={field.enumValues ?? []}
                tokenSeparators={[",", "，"]}
                options={(field.enumValues ?? []).map((value) => ({ value, label: value }))}
                onChange={(enumValues) => update(index, { enumValues })}
              />
            </div>
          )}
          </details>
          {renderLocations?.(field)}
          {field.type === "table" && (
            <details className="template-column-details"><summary>明细字段 · {field.columns.length} 项</summary>
            <TemplateFieldsEditor
              fields={field.columns}
              renderLocations={child => renderLocations?.(child, field)}
              onChange={(next) => update(index, { columns: next })}
              columns
              lockedKeys={lockedKeys}
            />
            </details>
          )}
          </div>
        </details>
      ))}
      {!lockedKeys && (
        <button
          className="sys-btn sys-btn--default"
          type="button"
          onClick={() => onChange([...fields, newTemplateField()])}
        >
          <Plus size={15} />
          添加{columns ? "数据列" : "输出字段"}
        </button>
      )}
    </div>
  );
}

export function StructuredOutputEditor({
  value,
  onChange,
}: {
  value?: StructuredOutputConfig;
  onChange: (value: StructuredOutputConfig) => void;
}) {
  const current = value ?? { enabled: false, variable: "", fields: [] };
  return (
    <section className="template-field-card">
      <div className="template-section-heading">
        <strong>
          <FileText size={16} />
          结构化结果
        </strong>
        <TemplateSelect
          value={current.enabled ? "enabled" : "disabled"}
          options={[
            { value: "disabled", label: "关闭，保持原文本输出" },
            { value: "enabled", label: "额外输出业务字段" },
          ]}
          onChange={(next) => onChange({ ...current, enabled: next === "enabled" })}
        />
      </div>
      {current.enabled && (
        <>
          <p className="sys-field-hint">
            一次生成多个字段，另存为对象变量。原文本输出继续保留；字段内容经过校验后才能用于下游交付。
          </p>
          <TemplateInput
            label="对象结果变量"
            value={current.variable}
            onChange={(variable) => onChange({ ...current, variable })}
            placeholder="例如 company_analysis_data"
          />
          <TemplateFieldsEditor
            fields={current.fields}
            onChange={(fields) => onChange({ ...current, fields })}
          />
        </>
      )}
    </section>
  );
}

export function TemplateValueEditor({
  field,
  value,
  onChange,
}: {
  field: TemplateField;
  value: unknown;
  onChange: (value: unknown) => void;
}) {
  const id = useId();
  if (field.type === "table") {
    const rows = Array.isArray(value) ? (value as Record<string, unknown>[]) : [];
    return (
      <div className="template-field-card template-value-table">
        <div className="template-section-heading"><strong>{field.label || field.key}<span className="template-value-count">{rows.length} 条</span></strong>
          <button type="button" className="sys-btn sys-btn--default sys-btn--sm" onClick={() => onChange([...rows, {}])}><Plus size={14} />添加明细</button>
        </div>
        {rows.length === 0 && <p className="sys-field-hint">暂无试填明细，可添加一条数据检查展开效果。</p>}
        {rows.map((row, index) => (
          <div className="template-table-row" key={index}>
            <div className="template-row-heading"><span>第 {index + 1} 条</span><button type="button" aria-label={`删除第 ${index + 1} 条明细`} className="template-icon-button" onClick={() => onChange(rows.filter((_, i) => i !== index))}><Trash2 size={15} /></button></div>
            <div className="template-form-grid">
              {field.columns.map((column) => (
                <TemplateValueEditor
                  key={column.key}
                  field={column}
                  value={row[column.key]}
                  onChange={(next) =>
                    onChange(
                      rows.map((item, i) => (i === index ? { ...item, [column.key]: next } : item)),
                    )
                  }
                />
              ))}
            </div>
          </div>
        ))}
      </div>
    );
  }
  if (field.enumValues?.length)
    return (
      <div className="sys-field">
        <label className="sys-field-label">{field.label || field.key}</label>
        <TemplateSelect
          value={String(value ?? "")}
          options={field.enumValues.map((value) => ({ value, label: value }))}
          onChange={onChange}
        />
      </div>
    );
  if (field.type === "boolean")
    return (
      <div className="sys-field">
        <label className="sys-field-label">{field.label || field.key}</label>
        <TemplateSelect
          value={String(value ?? false)}
          options={[
            { value: "true", label: "是" },
            { value: "false", label: "否" },
          ]}
          onChange={(next) => onChange(next === "true")}
        />
      </div>
    );
  if (["paragraphs", "markdown", "text"].includes(field.type))
    return (
      <div className="sys-field template-value-field">
        <label className="sys-field-label" htmlFor={id}>
          {field.label || field.key}
          {field.type === "paragraphs" ? "（空行分段）" : ""}
        </label>
        <Input.TextArea
          id={id}
          className={`sys-field-textarea template-value-textarea${field.type === "text" ? " template-value-textarea--short" : ""}`}
          autoSize={{ minRows: field.type === "text" ? 1 : 3, maxRows: 8 }}
          value={Array.isArray(value) ? value.join("\n\n") : String(value ?? "")}
          onChange={(event) =>
            onChange(
              field.type === "paragraphs"
                ? event.target.value.split(/\n\s*\n/)
                : event.target.value,
            )
          }
        />
      </div>
    );
  return (
    <TemplateInput
      label={field.label || field.key}
      type={field.type === "number" ? "number" : "date"}
      value={String(value ?? "")}
      onChange={(next) =>
        onChange(field.type === "number" ? (next === "" ? null : Number(next)) : next)
      }
    />
  );
}

/** 试填与流程内固定内容共用字段控件；短字段并列，正文和明细独占一行，避免大量空白文本框。 */
export function TemplateValuesEditor({ fields, values, onChange }: {
  fields: TemplateField[];
  values: Record<string, unknown>;
  onChange: (values: Record<string, unknown>) => void;
}) {
  return <div className="template-values-grid">{fields.map(field => <div key={field.key}
    className={field.type === "table" ? "template-values-item template-values-item--wide" : `template-value-card${["paragraphs", "markdown"].includes(field.type) ? " template-values-item--wide" : ""}`}>
    <TemplateValueEditor field={field} value={values[field.key]} onChange={value => onChange({ ...values, [field.key]: value })} />
    {field.type !== "table" && <span className="template-value-meta">{field.label && field.label !== field.key && <code>{field.key}</code>}<span>{templateFieldTypes.find(type => type.value === field.type)?.label}{field.required ? " · 必填" : " · 选填"}</span></span>}
  </div>)}</div>;
}
