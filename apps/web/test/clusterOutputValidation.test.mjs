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
const { validateWorkflowNode } = await import("../src/surfaces/designer/workflowNodeValidation.ts");
const cluster = (mode, outputs, enabled = true) => ({
  id: "cluster", data: { label: "报告集群", nodeType: "parallel_group", outputVariables: outputs, rawConfig: {
    executionMode: mode, clusterOutputVariable: "cluster_result", mergeRule: "{{monthly_output}}",
    intentModelProviderId: "model", intentSelectionMode: "single", intentInputTemplate: "{{report_year}}",
    intentFallbackMode: "fixed_reply", fallbackReply: "未命中报告",
    intentRoutes: [{ intentCode: "monthly", intentName: "月报", intentDescription: "生成月报", agentId: "monthly" }],
    clusterAgents: [{ id: "monthly", name: "月报", output: "monthly_output", modelProviderId: "model",
      systemPromptTemplateId: "none", userPromptTemplateId: "none", systemPrompt: "生成月报", userPrompt: "报告年月：{{report_year}}",
      structuredOutput: { enabled, variable: "attachment_meta", fields: [{ key: "data_date", label: "截止日期", type: "text", required: true }] }
    }]
  }}
});
const issues = (mode, outputs, enabled = true) => validateWorkflowNode(cluster(mode, outputs, enabled), new Set(["report_year"]));
test("三种集群模式允许显式声明的结构化输出，普通文本规则保持", () => {
  for (const mode of ["intent", "collaborative", "relay"]) {
    const outputs = mode === "intent" ? ["cluster_result", "attachment_meta"] : ["cluster_result", "monthly_output", "attachment_meta"];
    assert.deepEqual(issues(mode, outputs), [], mode);
  }
});
test("意图模式仍拦截子智能体普通文本、未知变量及已关闭的结构化输出", () => {
  for (const extra of ["monthly_output", "unconfigured_data"]) {
    assert.ok(issues("intent", ["cluster_result", "attachment_meta", extra]).some(x => x.code === "WORKFLOW_NODE_CLUSTER_INTENT_OUTPUT_INVALID"));
  }
  assert.ok(issues("intent", ["cluster_result", "attachment_meta"], false).some(x => x.code === "WORKFLOW_NODE_CLUSTER_INTENT_OUTPUT_INVALID"));
});
test("启用的结构化输出必须登记，关闭后原有集群配置继续通过", () => {
  for (const mode of ["intent", "collaborative", "relay"]) {
    const outputs = mode === "intent" ? ["cluster_result"] : ["cluster_result", "monthly_output"];
    assert.ok(issues(mode, outputs).some(x => x.code.endsWith("OUTPUT_INVALID") || x.code.endsWith("OUTPUT_MISMATCH")), mode);
    assert.deepEqual(issues(mode, outputs, false), [], mode);
  }
});
