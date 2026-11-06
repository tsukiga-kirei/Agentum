import assert from "node:assert/strict";
import { test } from "node:test";
import { inferTemplateExcelRegions, newTemplateField } from "../src/types/delivery-template.ts";
const table = (key, columns) => ({ ...newTemplateField(key), type: "table", columns: columns.map(newTemplateField) });
const location = (field, column, address) => ({ id: `投资业务!${address}:${field}.${column}`, field, sheet: "投资业务", label: "", style: {} });
const spec = (fields, locations) => ({ fields, locations, defaultStyle: {}, sheetStyles: {} });
test("并列债券和基金分别识别起点，允许独立条数", () => {
  assert.deepEqual(inferTemplateExcelRegions(spec([table("bonds", ["name", "amount"]), table("funds", ["name"])], [location("bonds", "name", "A2"), location("bonds", "amount", "B2"), location("funds", "name", "H2")])), [
    { sheet: "投资业务", field: "bonds", startCell: "A2", direction: "vertical" },
    { sheet: "投资业务", field: "funds", startCell: "H2", direction: "vertical" },
  ]);
});
test("竖排字段标记识别为横向记录扩展", () => {
  assert.equal(inferTemplateExcelRegions(spec([table("items", ["name", "amount"])], [location("items", "name", "B1"), location("items", "amount", "B2")]))[0].direction, "horizontal");
});
test("重复和不连续标记不盲目识别", () => {
  const fields = [table("items", ["name", "amount"])];
  assert.throws(() => inferTemplateExcelRegions(spec(fields, [location("items", "name", "B1"), location("items", "amount", "B3")])), /不连续/);
  assert.throws(() => inferTemplateExcelRegions(spec(fields, [location("items", "name", "A1"), location("items", "name", "A2"), location("items", "amount", "B1")])), /重复/);
});
