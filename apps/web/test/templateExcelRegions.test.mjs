import assert from "node:assert/strict";
import { test } from "node:test";
import { inferTemplateExcelRegions, newTemplateField } from "../src/types/delivery-template.ts";
import { buildMcpDatasetSources } from "../src/utils/mcpDatasetSources.ts";
const table = (key, columns) => ({ ...newTemplateField(key), type: "table", columns: columns.map(newTemplateField) });
const location = (field, column, address, sheet = "投资业务") => ({ id: `${sheet}!${address}:${field}.${column}`, field, sheet, label: "", style: {} });
const spec = (fields, locations) => ({ fields, locations, defaultStyle: {}, sheetStyles: {} });
test("普通纵向明细、并列列表均登记全部字段位置", () => {
  const regions = inferTemplateExcelRegions(spec([table("bonds", ["name", "amount"]), table("funds", ["name"])], [location("bonds", "name", "A2"), location("bonds", "amount", "B2"), location("funds", "name", "H2")]));
  assert.deepEqual(regions.map(region => [region.field, region.column, region.startCell, region.direction]), [["bonds", "name", "A2", "vertical"], ["bonds", "amount", "B2", "vertical"], ["funds", "name", "H2", "vertical"]]);
});
test("同列不同字段默认横向，跨 Sheet 及重复字段不丢失位置，独立方向可保留", () => {
  const source = spec([table("items", ["name", "amount"])], [location("items", "name", "B1"), location("items", "amount", "B2"), location("items", "name", "H4", "汇总")]);
  const regions = inferTemplateExcelRegions(source);
  assert.deepEqual(regions.map(region => region.direction), ["horizontal", "horizontal", "vertical"]);
  regions[2].direction = "horizontal";
  assert.deepEqual(inferTemplateExcelRegions({ ...source, excelRegions: regions }), regions);
});
test("同一个字段重复标记和分散字段都保留，无需连续排列", () => {
  const fields = [table("items", ["name", "amount"])];
  assert.equal(inferTemplateExcelRegions(spec(fields, [location("items", "name", "A1"), location("items", "name", "D1"), location("items", "amount", "H2")])).length, 3);
});
test("MCP 来源只展示上游智能体实际配置的工具，集群成员与能力互相隔离", () => {
  const nodes = [
    { id: "input", data: { nodeType: "user_input", label: "输入" } },
    { id: "cluster", data: { nodeType: "parallel_group", label: "采集", rawConfig: { clusterAgents: [{ name: "债券", mcpIds: ["abc-1"] }, { name: "基金", mcpIds: ["abc-1"] }] } } },
    { id: "delivery", data: { nodeType: "delivery", label: "交付" } },
    { id: "later", data: { nodeType: "agent", label: "后续", rawConfig: { mcpIds: ["abc-1"] } } },
  ];
  const capabilities = [{ id: "abc-1", name: "金融", assetType: "mcp", config: { toolResultPolicies: { rows: { mode: "dataset", datasetKey: "data", columns: ["name", "amount"] }, text: { mode: "direct" } } } }];
  const sources = buildMcpDatasetSources(nodes, capabilities, "delivery");
  assert.deepEqual(sources.map(source => source.datasetPath), ["member_0.cap_abc_1.data", "member_1.cap_abc_1.data"]);
  assert.deepEqual(sources[0].datasetColumns, ["name", "amount"]);
  assert.ok(sources.every(source => source.sourceNodeId === "cluster"));
});
