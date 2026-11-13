import assert from "node:assert/strict";
import { test } from "node:test";
import { setTemplateFieldsRequired, templateFileNameVariables, newTemplateField } from "../src/types/delivery-template.ts";
import { copyMcpResultPolicy } from "../src/utils/mcpResultPolicies.ts";

test("批量必填/选填包含明细列，原配置和其他属性保留", () => {
  const fields = [{ ...newTemplateField("items"), type: "table", columns: [{ ...newTemplateField("amount"), type: "number" }], maxItems: 100000 }];
  const optional = setTemplateFieldsRequired(fields, false);
  assert.equal(optional[0].required, false);
  assert.equal(optional[0].columns[0].required, false);
  assert.equal(optional[0].columns[0].type, "number");
  assert.equal(optional[0].maxItems, 100000);
  assert.equal(fields[0].required, true);
  assert.deepEqual(setTemplateFieldsRequired(optional, true), fields);
});
test("复制 MCP 规则保留分页与字段，独立编辑且结果名不冲突", () => {
  const policies = { loan: { mode: "dataset", datasetKey: "fund", columns: ["bal"], rowsPath: "$.rows", pagination: { type: "page", pageParam: "page_no", pageSize: 1000 } } };
  const result = copyMcpResultPolicy(policies, "loan", "fund");
  assert.equal(result.fund.datasetKey, "fund_2");
  assert.equal(result.fund.pagination.pageParam, "page_no");
  result.fund.pagination.pageParam = "changed";
  result.fund.columns.push("date");
  assert.equal(policies.loan.pagination.pageParam, "page_no");
  assert.deepEqual(policies.loan.columns, ["bal"]);
  assert.equal(copyMcpResultPolicy(result, "loan", "fund"), result);
});
test("文件名选择包含系统、输入和对象标量字段，排除明细/对象整体", () => {
  const options = templateFileNameVariables([
    { name: "report_year", type: "string", sourceNodeId: "input", sourceNodeName: "输入信息" },
    { name: "attachment_meta", type: "object", sourceNodeId: "agent", sourceNodeName: "附表取数", fields: [newTemplateField("data_date"), { ...newTemplateField("items"), type: "table", columns: [] }] },
    { name: "mcp_datasets", type: "object", datasetPath: "cap.loan", sourceNodeId: "agent", sourceNodeName: "工具" },
  ]);
  assert.deepEqual(options.map(option => option.value), ["runNumber", "date", "dateCompact", "report_year", "attachment_meta.data_date"]);
});
