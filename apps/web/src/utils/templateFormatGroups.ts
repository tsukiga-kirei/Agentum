import type { TemplateField, TemplateFieldPreset, TemplateFormatGroup, TemplateLocation, TemplateSpec, TemplateStyle } from "../types/delivery-template";
import { sampleTemplateValue } from "../types/delivery-template";

export const templateFieldPresets: { value: TemplateFieldPreset; label: string; type?: TemplateField["type"]; style: TemplateStyle; hint: string }[] = [
  { value: "amount", label: "金额", type: "number", style: { numberFormat: "#,##0.00", alignment: "right" }, hint: "数字 · 千位分隔 · 两位小数" },
  { value: "rate", label: "汇率", type: "number", style: { numberFormat: "0.000000", alignment: "right" }, hint: "数字 · 六位小数" },
  { value: "text", label: "文字", type: "text", style: { numberFormat: "@", alignment: "left" }, hint: "文字 · 保留账号、编号和前导零" },
  { value: "number", label: "普通数字", type: "number", style: { numberFormat: "General", alignment: "right" }, hint: "数字 · 常规显示" },
  { value: "date", label: "日期", type: "date", style: { numberFormat: "yyyy-mm-dd" }, hint: "日期 · 年-月-日" },
  { value: "boolean", label: "是／否", type: "boolean", style: { numberFormat: "General" }, hint: "布尔值" },
  { value: "custom", label: "自定义样式", style: {}, hint: "仅设置样式，保留字段类型" },
];

export type TemplateFieldTarget = { path: string; label: string; group: string; type: TemplateField["type"]; sheets: string[]; positions: string[] };

/** 同一完整字段标识只列一次，跨 Sheet 的位置随字段一起选择。 */
export function templateFieldTargets(fields: TemplateField[], locations: TemplateLocation[] = []): TemplateFieldTarget[] {
  return fields.flatMap(field => {
    const leaves = field.type === "table" ? field.columns.map(column => ({ field: column, path: `${field.key}.${column.key}`, group: field.label || field.key })) : [{ field, path: field.key, group: "普通字段" }];
    return leaves.filter(item => item.field.key).map(item => {
      const matches = locations.filter(location => location.id.endsWith(`:${item.path}`));
      return { path: item.path, label: item.field.label || item.field.key, group: item.group, type: item.field.type,
        sheets: [...new Set(matches.map(location => location.sheet).filter(Boolean))], positions: matches.map(location => location.label || location.id) };
    });
  });
}

export function setTemplateFieldTypes(fields: TemplateField[], paths: string[], preset: TemplateFieldPreset): TemplateField[] {
  const type = templateFieldPresets.find(item => item.value === preset)?.type;
  if (!type) return fields;
  const selected = new Set(paths);
  const change = (field: TemplateField, path: string): TemplateField => {
    if (field.type === "table") return { ...field, columns: field.columns.map(column => change(column, `${path}.${column.key}`)) };
    if (!selected.has(path)) return field;
    return { ...field, type, ...(type !== "text" ? { enumValues: [] } : {}) };
  };
  return fields.map(field => change(field, field.key));
}

/** 单独修改类型或删除字段时同步移出不兼容的方案，不能留下表面是文字、方案却要求数字的配置。 */
export function reconcileTemplateFormatGroups(spec: TemplateSpec, fields: TemplateField[]): TemplateSpec {
  const types = new Map(templateFieldTargets(fields).map(item => [item.path, item.type]));
  return { ...spec, fields, formatGroups: (spec.formatGroups ?? []).map(group => {
    const type = templateFieldPresets.find(item => item.value === group.preset)?.type;
    return { ...group, fields: group.fields.filter(path => types.has(path) && (!type || types.get(path) === type)) };
  }) };
}

/** 应用新方案会把选中字段移出其他方案，类型与格式一次更新；不改原件、位置及数组顺序。 */
export function assignTemplateFormatGroup(spec: TemplateSpec, group: TemplateFormatGroup): TemplateSpec {
  const known = new Set(templateFieldTargets(spec.fields).map(item => item.path));
  const paths = [...new Set(group.fields)].filter(path => known.has(path));
  const selected = new Set(paths);
  const groups = (spec.formatGroups ?? []).filter(item => item.id !== group.id).map(item => ({ ...item, fields: item.fields.filter(path => !selected.has(path)) }));
  const index = (spec.formatGroups ?? []).findIndex(item => item.id === group.id);
  groups.splice(index < 0 ? groups.length : index, 0, { ...group, fields: paths });
  return { ...spec, fields: setTemplateFieldTypes(spec.fields, paths, group.preset), formatGroups: groups };
}

export function applyTemplateFieldPreset(spec: TemplateSpec, paths: string[], preset: TemplateFieldPreset): TemplateSpec {
  const definition = templateFieldPresets.find(item => item.value === preset)!;
  const id = `preset_${preset}`;
  const current = spec.formatGroups?.find(group => group.id === id);
  return assignTemplateFormatGroup(spec, { id, name: definition.label, preset, style: { ...definition.style }, fields: [...(current?.fields ?? []), ...paths] });
}

/** 推荐只基于已声明类型和名称，不依据看似数字的实际值猜测账号或合同编号。 */
export function recommendedTemplateFields(targets: TemplateFieldTarget[], preset: TemplateFieldPreset): string[] {
  return targets.filter(target => {
    const key = target.path.slice(target.path.lastIndexOf(".") + 1);
    const name = `${key} ${target.label}`;
    const identifier = /编号|账号|账户号|代码|合同号/.test(target.label) || /(?:^|_)(?:no|num|code|id)(?:_|$)/i.test(key);
    if (preset === "text") return identifier;
    if (identifier) return false;
    if (preset === "rate") return /汇率|midprice|exchange_rate/i.test(name);
    if (preset === "amount") return (/金额|余额|面值|价款/.test(target.label) || /(?:^|_)(?:bal|balance|amount)(?:_|$)/i.test(key)) && !/汇率|midprice|exchange_rate/i.test(name);
    if (preset === "date") return target.type === "date" || (/日期|起息日|到期日/.test(target.label) || /(?:^|_)date(?:_|$)|ksrq|jsrq/i.test(key));
    if (preset === "number") return target.type === "number";
    if (preset === "boolean") return target.type === "boolean";
    return false;
  }).map(target => target.path);
}

/** 只调整类型发生变化的试填值；用户已填的有效数字、空位与其他字段不被批量操作重置。 */
export function synchronizeTemplateTrialValues(before: TemplateField[], after: TemplateField[], values: Record<string, unknown>): Record<string, unknown> {
  const previous = new Map(templateFieldTargets(before).map(target => [target.path, target.type]));
  const convert = (field: TemplateField, path: string, value: unknown): unknown => {
    if (previous.get(path) === field.type || value == null || value === "") return value;
    if (field.type === "number") {
      if (typeof value === "number" && Number.isFinite(value)) return value;
      if (typeof value === "string" && value.trim() && Number.isFinite(Number(value))) return Number(value);
    } else if (field.type === "date") {
      const text = String(value).replace(/^(\d{4})(\d{2})(\d{2})$/, "$1-$2-$3");
      if (/^\d{4}-\d{2}-\d{2}$/.test(text)) {
        const date = new Date(`${text}T00:00:00Z`);
        if (Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === text) return text;
      }
    } else if (field.type === "text") return String(value);
    else if (field.type === "boolean") {
      if (typeof value === "boolean") return value;
      if (value === "true" || value === "false") return value === "true";
    }
    return sampleTemplateValue(field);
  };
  return Object.fromEntries(after.map(field => {
    const value = values[field.key];
    if (field.type !== "table" || !Array.isArray(value)) return [field.key, convert(field, field.key, value)];
    return [field.key, value.map(row => {
      if (!row || typeof row !== "object" || Array.isArray(row)) return row;
      return Object.fromEntries(field.columns.map(column => [column.key, convert(column, `${field.key}.${column.key}`, row[column.key])]));
    })];
  }));
}
