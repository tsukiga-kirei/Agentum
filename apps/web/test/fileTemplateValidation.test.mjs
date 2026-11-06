import assert from "node:assert/strict";
import { test } from "node:test";
import { registerHooks } from "node:module";
// 项目源码沿用 Vite 的无扩展名导入；测试进程只为本地相对路径补齐 TypeScript 后缀。
registerHooks({
  load(url, context, nextLoad) {
    const loaded = nextLoad(url, context);
    // 单元测试没有 Vite 注入的构建环境；只将 API 根地址回退到源码的默认值。
    if (url.endsWith("/services/apiClient.ts"))
      return {
        ...loaded,
        source: Buffer.from(loaded.source)
          .toString("utf8")
          .replace("import.meta.env.VITE_API_BASE_URL", "undefined"),
      };
    return loaded;
  },
  resolve(specifier, context, nextResolve) {
    try {
      return nextResolve(specifier, context);
    } catch (error) {
      if (
        error.code === "ERR_MODULE_NOT_FOUND" &&
        specifier.startsWith(".") &&
        !/\.[a-z]+$/i.test(specifier)
      )
        return nextResolve(`${specifier}.ts`, context);
      throw error;
    }
  },
});
const { collectRuntimeTemplateVariableNames, collectRuntimeTemplateTextFields } = await import(
  "../src/surfaces/designer/workflowNodeValidation.ts"
);

test("模板模式只读取字段来源，保留但忽略原内容模式的配置", () => {
  const config = {
    deliveryMode: "capability",
    documentKind: "word",
    generationMode: "file_template",
    markdownContent: "{{old_report}}",
    fileNameTemplate: "报告-{{runNumber}}.docx",
    templateBindings: [
      {
        field: "summary",
        variable: "analysis_data",
        constant: false,
      },
      { field: "name", constant: true, value: "{{literal_text}}" },
    ],
  };
  assert.deepEqual(collectRuntimeTemplateVariableNames(config, "delivery"), [
    "runNumber",
    "analysis_data",
  ]);
});

test("未启用模板时仍按原 Word 正文和文件名收集引用", () => {
  const fields = collectRuntimeTemplateTextFields(
    {
      deliveryMode: "capability",
      documentKind: "word",
      markdownContent: "{{report}}",
      fileNameTemplate: "{{runNumber}}.docx",
      templateBindings: [{ variable: "unused", constant: false }],
    },
    "delivery",
  );
  assert.deepEqual(
    fields.map((field) => field.key),
    ["markdownContent", "fileNameTemplate"],
  );
});

test("多交付项按自身模式独立收集引用", () => {
  const config = {
    deliveryConfigMode: "multiple",
    deliveryItems: [
      {
        name: "模板报告",
        config: {
          deliveryMode: "capability",
          documentKind: "word",
          generationMode: "file_template",
          markdownContent: "{{stale}}",
          templateBindings: [{ variable: "analysis_data", constant: false }],
        },
      },
      {
        name: "原有表格",
        config: {
          deliveryMode: "capability",
          documentKind: "excel",
          excelSheets: [{ name: "明细", bodyTemplate: "{{old_table}}" }],
        },
      },
    ],
  };
  assert.deepEqual(collectRuntimeTemplateVariableNames(config, "delivery"), [
    "analysis_data",
    "old_table",
  ]);
});
