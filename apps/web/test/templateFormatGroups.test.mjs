import assert from "node:assert/strict";
import { test } from "node:test";
import { registerHooks } from "node:module";
// Vite 源码沿用无扩展名导入，Node 测试仅补齐本地 TypeScript 路径。
registerHooks({ resolve(specifier, context, nextResolve) {
  try { return nextResolve(specifier, context); }
  catch (error) {
    if (error.code === "ERR_MODULE_NOT_FOUND" && specifier.startsWith(".") && !/\.[a-z]+$/i.test(specifier)) return nextResolve(`${specifier}.ts`, context);
    throw error;
  }
} });
import { newTemplateField } from "../src/types/delivery-template.ts";
const { applyTemplateFieldPreset, assignTemplateFormatGroup, reconcileTemplateFormatGroups, recommendedTemplateFields, setTemplateFieldTypes, synchronizeTemplateTrialValues, templateFieldTargets } = await import("../src/utils/templateFormatGroups.ts");

const field = (key, label = key) => ({ ...newTemplateField(key), label });
const fields = [field("data_date", "报告日期"), { ...field("items", "业务明细"), type: "table", columns: [field("bal"), field("bal_cny", "人民币余额"), field("midprice", "汇率"), field("contract_num", "合同编号"), field("start_date", "开始日期")] }];
const location = (sheet, address, path) => ({ id: `${sheet}!${address}:${path}`, field: path.split(".")[0], sheet, label: `${sheet} · ${address}`, style: {} });
const spec = () => ({ fields: structuredClone(fields), locations: [location("明细", "B2", "items.bal"), location("重复", "H8", "items.bal"), location("明细", "C2", "items.bal_cny")], defaultStyle: { fontSize: 11 }, sheetStyles: {} });

test("字段选择合并跨 Sheet 的重复位置，推荐金额不误选编号或汇率", () => {
  const source = spec(), targets = templateFieldTargets(source.fields, source.locations);
  assert.equal(targets.length, 6);
  assert.deepEqual(targets.find(item => item.path === "items.bal").sheets, ["明细", "重复"]);
  assert.deepEqual(recommendedTemplateFields(targets, "amount"), ["items.bal", "items.bal_cny"]);
  assert.deepEqual(recommendedTemplateFields(targets, "rate"), ["items.midprice"]);
  assert.deepEqual(recommendedTemplateFields(targets, "text"), ["items.contract_num"]);
  assert.deepEqual(recommendedTemplateFields(targets, "date"), ["data_date", "items.start_date"]);
});
test("金额批量同时设置数字及两位小数，保留未选字段、位置和原配置", () => {
  const source = spec();
  source.fields[1].columns[0].enumValues = ["100"];
  const result = applyTemplateFieldPreset(source, ["items.bal", "items.bal_cny"], "amount");
  assert.deepEqual(result.fields[1].columns.slice(0, 2).map(item => item.type), ["number", "number"]);
  assert.deepEqual(result.fields[1].columns[0].enumValues, []);
  assert.equal(result.fields[1].columns[3].type, "text");
  assert.deepEqual(result.formatGroups[0].style, { numberFormat: "#,##0.00", alignment: "right" });
  assert.equal(source.fields[1].columns[0].type, "text");
  assert.equal(source.formatGroups, undefined);
  assert.deepEqual(result.locations, source.locations);
});
test("移入新方案不会静默叠加，金额/汇率独立，移出或删除样式不回滚类型", () => {
  let result = applyTemplateFieldPreset(spec(), ["items.bal", "items.bal_cny"], "amount");
  result = applyTemplateFieldPreset(result, ["items.midprice"], "rate");
  result = assignTemplateFormatGroup(result, { id: "custom_amount", name: "高精度金额", preset: "amount", fields: ["items.bal"], style: { numberFormat: "0.0000" } });
  assert.deepEqual(result.formatGroups[0].fields, ["items.bal_cny"]);
  assert.equal(result.formatGroups[1].style.numberFormat, "0.000000");
  result = assignTemplateFormatGroup(result, { ...result.formatGroups[2], fields: [] });
  assert.equal(result.fields[1].columns[0].type, "number");
  result = applyTemplateFieldPreset(result, ["items.bal_cny"], "text");
  assert.deepEqual(result.formatGroups[0].fields, []);
  assert.equal(result.fields[1].columns[1].type, "text");
});
test("单独改类型时同步移出不兼容的方案，明细容器不能被批量变成数字", () => {
  const source = applyTemplateFieldPreset(spec(), ["items.bal", "items.bal_cny"], "amount");
  const changed = setTemplateFieldTypes(source.fields, ["items", "items.bal"], "text");
  const result = reconcileTemplateFormatGroups(source, changed);
  assert.equal(result.fields[1].type, "table");
  assert.deepEqual(result.formatGroups[0].fields, ["items.bal_cny"]);
  assert.deepEqual(result.locations, source.locations);
});

test("批量预设后试填值适配新类型，保留零值、空位及无关编号", () => {
  const source = spec();
  const next = applyTemplateFieldPreset(source, ["items.bal", "items.bal_cny"], "amount");
  const values = { data_date: "日期示例", items: [{ bal: "金额示例", bal_cny: "0.000000", contract_num: "0008" }, { bal: null, bal_cny: "123.456789" }] };
  const result = synchronizeTemplateTrialValues(source.fields, next.fields, values);
  assert.equal(typeof result.items[0].bal, "number");
  assert.equal(result.items[0].bal_cny, 0);
  assert.equal(result.items[0].contract_num, "0008");
  assert.equal(result.items[1].bal, null);
  assert.equal(result.items[1].bal_cny, 123.456789);
  assert.equal(result.data_date, "日期示例");
  assert.equal(values.items[0].bal, "金额示例");
});
