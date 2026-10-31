import { useId } from "react";
import { Select } from "antd";
import { FileText, Hash, Plus, Trash2, Type, Settings2, Tags } from "lucide-react";
import { newTemplateField, templateFieldTypes } from "../../types/delivery-template";
import type {
  StructuredOutputConfig,
  TemplateField,
  TemplateStyle,
} from "../../types/delivery-template";
import "./TemplateEditors.css";

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
  return (
    <div className="sys-field">
      <label className="sys-field-label" htmlFor={id}>
        {label}
      </label>
      <div className="sys-field-input-wrap">
        <Type size={16} className="sys-field-prefix" />
        <input
          id={id}
          className="sys-field-input"
          type={type}
          value={value}
          onChange={(event) => onChange(event.target.value)}
          disabled={disabled}
          placeholder={placeholder}
        />
      </div>
    </div>
  );
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
  return (
    <div className="template-style-editor">
      <p className="sys-field-hint">
        空白表示沿用原有或上层格式；填写 0 可取消缩进或段间距。格式只作用于当前范围的填充内容。
      </p>
      <div className="template-form-grid">
        <div className="sys-field">
          <label className="sys-field-label">样式预设</label>
          <TemplateSelect
            value="custom"
            options={[
              { value: "custom", label: "当前设置 / 自定义" },
              { value: "inherit", label: "保持原有格式" },
              { value: "body", label: "报告正文" },
              { value: "risk", label: "风险说明" },
              { value: "amount", label: "金额明细" },
            ]}
            onChange={(preset) =>
              onChange(
                preset === "inherit"
                  ? {}
                  : preset === "body"
                    ? kind === "word"
                      ? { fontFamily: "宋体", fontSize: 12, firstLineChars: 2, lineSpacing: 1.5 }
                      : { fontFamily: "宋体", fontSize: 12, wrapText: true }
                    : preset === "risk"
                      ? { color: "9C2F2F", backgroundColor: "FFF1F0", wrapText: true }
                      : preset === "amount"
                        ? { numberFormat: "#,##0.00", alignment: "right" }
                        : value,
              )
            }
          />
        </div>
        <TemplateInput
          label="字体"
          value={String(value.fontFamily ?? "")}
          onChange={(next) => update("fontFamily", next)}
          placeholder="沿用已有字体"
        />
        {numeric.map(([key, label]) => (
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
}: {
  fields: TemplateField[];
  onChange: (fields: TemplateField[]) => void;
  lockedKeys?: boolean;
  columns?: boolean;
}) {
  const update = (index: number, patch: Partial<TemplateField>) =>
    onChange(fields.map((field, i) => (i === index ? { ...field, ...patch } : field)));
  return (
    <div className="template-field-list">
      {fields.map((field, index) => (
        <div className="template-field-card" key={`${index}`}>
          <div className="template-section-heading">
            <strong>
              <Hash size={15} />
              {field.label || field.key || `字段 ${index + 1}`}
            </strong>
            {!lockedKeys && (
              <button
                className="sys-btn sys-btn--default"
                type="button"
                aria-label="删除字段"
                onClick={() => onChange(fields.filter((_, i) => i !== index))}
              >
                <Trash2 size={14} />
              </button>
            )}
          </div>
          <div className="template-form-grid">
            <TemplateInput
              label="字段标识"
              value={field.key}
              onChange={(key) => update(index, { key })}
              disabled={lockedKeys}
              placeholder="例如 risk_summary"
            />
            <TemplateInput
              label="字段名称"
              value={field.label}
              onChange={(label) => update(index, { label })}
            />
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
          {field.type === "text" && (
            <div className="sys-field">
              <label className="sys-field-label">允许的选项（输入后回车，留空不限制）</label>
              <Select
                prefix={<Tags size={16} className="text-[var(--color-text-tertiary)]" />}
                mode="tags"
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
          <TemplateInput
            label="内容要求"
            value={field.description}
            onChange={(description) => update(index, { description })}
            placeholder="说明内容含义、依据和限制；外观格式在填写位置中设置"
          />
          {field.type === "table" && (
            <TemplateFieldsEditor
              fields={field.columns}
              onChange={(next) => update(index, { columns: next })}
              columns
              lockedKeys={lockedKeys}
            />
          )}
        </div>
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
  if (field.type === "table") {
    const rows = Array.isArray(value) ? (value as Record<string, unknown>[]) : [];
    return (
      <div className="template-field-card">
        <strong>{field.label || field.key}</strong>
        {rows.map((row, index) => (
          <div className="template-table-row" key={index}>
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
            <button
              type="button"
              className="sys-btn sys-btn--default"
              onClick={() => onChange(rows.filter((_, i) => i !== index))}
            >
              删除此行
            </button>
          </div>
        ))}
        <button
          type="button"
          className="sys-btn sys-btn--default"
          onClick={() => onChange([...rows, {}])}
        >
          <Plus size={14} />
          添加明细
        </button>
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
      <div className="sys-field">
        <label className="sys-field-label">
          {field.label || field.key}
          {field.type === "paragraphs" ? "（空行分段）" : ""}
        </label>
        <textarea
          className="sys-field-textarea"
          rows={3}
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
