# 流程创建与运行态节点执行检查说明

更新时间：2026-06-11

本文记录本轮对“创建流程节点、发布校验、运行态中断 / 重新执行 / 恢复进度”的全链路检查结论和修复口径。适用范围是当前阶段一已落地的线性阶段积木流程：系统触发 -> 输入 / 智能体 / 智能体集群 / 交付等业务积木 -> 结束。

## 1. 本轮问题结论

用户看到的错误：

```json
{
  "code": "WORKBENCH_NODE_RESTART_INVALID",
  "message": "当前步骤状态不支持重新执行"
}
```

根因是后端 `restartNode` 只允许以下节点重新执行：

- `canceled`、`failed`、`pending` 状态的节点。
- `completed` 状态的单智能体节点。

但前端运行详情会对配置了 `allowUserEdit` 或 `outputMode = 追问确认` 的智能体集群节点展示“重新执行”。因此已完成的 `parallel_group` 节点点击“重新执行”时，前端动作是合理的，后端白名单不完整，最终返回 `WORKBENCH_NODE_RESTART_INVALID`。

本轮已修复：已完成的单智能体节点和智能体集群节点都允许通过 `restart` 从头重跑；主动中断后的 `canceled` 节点只允许走“重新执行”，不再允许被 `recover` 伪装成恢复进度。

## 2. 创建流程的完整链路

### 2.1 后端设计目录

入口：`GET /api/tenants/{tenantId}/workflows/drafts/designer-catalog`

后端统一下发：

- `systemTrigger`：系统触发节点，固定存在。
- `brickTemplates`：当前可添加的输入节点、单智能体节点、智能体集群节点、交付节点。
- `variableMetadata`：默认变量元数据，例如 `starter`、`input_1`、`agent_response`、`cluster_result`、`delivery_record`。

设计原则：默认模板由后端维护，前端只负责渲染、局部补齐唯一变量名并保存用户编排结果，避免多个页面各自沉淀一套默认配置。

### 2.2 前端创建积木

前端文件：`apps/web/src/surfaces/designer/WorkflowEditorPage.tsx`

创建节点时会执行：

1. 根据后端模板生成 `nodeId`、`nodeType`、默认名称、默认说明。
2. 根据左侧积木顺序生成节点 `position` 和线性连线。
3. 自动把上一个积木的输出变量作为下一个积木的可用输入上下文。
4. 生成或同步节点输出变量，并写入右侧配置面板。

本轮修复的两个创建问题：

- 多个输入节点不再都沿用模板里的 `input_1`，而是按节点序号同步字段变量，例如 `input_1`、`input_2`。
- 新建智能体集群的子智能体输出变量使用可读短名，例如 `cluster_4_agent_1_output`、`cluster_4_agent_2_output`，避免把节点随机串暴露给流程设计者。

### 2.3 保存草稿

入口：`POST /api/tenants/{tenantId}/workflows/drafts/{workflowId}/graph`

保存内容：

- `nodes[]`：节点快照，包含 `nodeId`、`nodeType`、名称、坐标、输入变量、输出变量、配置 JSON。
- `edges[]`：当前阶段由前端按左侧积木顺序生成的线性连线。
- `variables[]`：每个节点输出变量的声明，包含类型、来源节点、敏感标记和交付标记。

后端保存前会做：

- 节点 ID 非空且不重复。
- 节点类型在白名单内。
- 连线引用的源节点和目标节点必须存在。
- 变量声明必须与节点输出变量一一对应。
- 节点引用的系统能力、租户自建能力、提示词模板必须仍在当前编辑者权限范围内。
- 输入节点和智能体集群节点的配置与输出变量必须一致。

### 2.4 发布校验

入口：`POST /api/tenants/{tenantId}/workflows/drafts/{workflowId}/validate`

当前阶段发布校验规则：

- 必须有且只有一个触发节点。
- 必须至少有一个交付节点。
- 除触发节点外，每个节点必须有上游连线。
- 除交付节点外，每个节点必须有下游连线。
- 不能存在循环。
- 每个节点输入变量必须能从上游输出解析。
- 输出变量不能重复。
- 当前阶段流程必须按左侧积木顺序单线串联，不能出现额外、重复或跨序连线。

说明：运行态当前按 `sortOrder` 顺序执行，不按图遍历执行。因此发布校验必须约束为线性链，避免出现“设计图看起来能过，运行时按另一个顺序执行”的不一致。

### 2.5 正式发布

入口：`POST /api/tenants/{tenantId}/workflows/drafts/{workflowId}/publish`

发布会再次执行图校验和能力引用校验，全部通过后写入不可变版本快照 `workflow_versions.definition_snapshot`。运行实例只读取发布快照，不读取后续草稿，避免草稿修改影响已发起任务。

### 2.6 发起运行

入口：`POST /api/tenants/{tenantId}/workbench/runs`

发起后：

- 按发布快照生成 `workflow_runs`。
- 按发布节点生成 `workflow_node_runs`，保存每个节点的配置快照、输入变量和输出变量。
- 用户输入 / 人工审核节点生成待办。
- 单智能体、智能体集群和交付节点通过 RabbitMQ 异步执行。
- Redis Stream 记录进度事件，前端通过 SSE 回放。

## 3. 节点类型配置规则

### 3.1 系统触发节点

节点类型：`trigger`

用途：运行实例发起时自动完成，写入发起人和发起时间等基础变量。

默认输出：

- `starter`
- `started_at`

约束：

- 只能有一个。
- 必须位于线性链起点。
- 用户不能在前端作为普通积木删除。

### 3.2 输入节点

节点类型：`user_input`

用途：收集用户填写的业务资料。

核心配置：

```json
{
  "brickType": "input",
  "inputFields": [
    {
      "id": "field_1",
      "label": "业务输入",
      "variable": "input_1",
      "placeholder": "请输入业务资料"
    }
  ]
}
```

约束：

- `inputFields` 不能为空。
- `inputFields[].variable` 必须是合法变量名：小写字母开头，只能包含小写字母、数字和下划线。
- 输入字段变量必须与节点 `outputVariables` 完全一致。
- 多个输入节点必须使用不同输出变量。

### 3.3 单智能体节点

节点类型：`agent`

用途：调用一个智能体完成分析、生成、判断、提取或追问。

核心配置：

```json
{
  "brickType": "agent",
  "agentAssetId": "custom",
  "systemPromptTemplateId": "none",
  "userPromptTemplateId": "none",
  "systemPrompt": "...",
  "userPrompt": "...",
  "skillIds": [],
  "mcpIds": [],
  "allowQuestion": true,
  "allowUserEdit": true,
  "outputMode": "追问确认"
}
```

约束：

- 自定义系统提示词时，`systemPrompt` 不能为空。
- 自定义用户提示词时，`userPrompt` 不能为空。
- 引用提示词模板、Skill、MCP、智能体模板时，后端必须重新校验当前编辑者是否可用。
- 完成后如允许修改或追问，可重新执行或追问。

### 3.4 智能体集群节点

节点类型：`parallel_group`

用途：多个子智能体协同处理、接力处理或按意图分派后汇总输出。

核心配置：

```json
{
  "brickType": "cluster",
  "executionMode": "collaborative",
  "clusterAgents": [
    {
      "id": "cluster_agent_1",
      "name": "资料核验智能体",
      "agentAssetId": "custom",
      "systemPromptTemplateId": "none",
      "userPromptTemplateId": "none",
      "systemPrompt": "...",
      "userPrompt": "...",
      "skillIds": [],
      "mcpIds": [],
      "output": "cluster_4_agent_1_output",
      "allowQuestion": true,
      "allowUserEdit": true
    }
  ]
}
```

约束：

- `clusterAgents` 不能为空。
- `executionMode` 支持 `collaborative`、`relay`、`intent`；历史 `parallel` / `sequential` 数据由迁移脚本清洗，不再作为运行态兼容值。
- 意图分派通过 `intentRoutes` 配置“意图名称 / 命中说明 / 目标子智能体”，运行时只执行命中的子智能体；多个命中按意图清单顺序写入集群输出模板，未命中可中止、转交指定智能体或返回固定话术。
- 每个子智能体的 `output` 必须是合法变量名。
- 每个子智能体的 `output` 不能重复。
- 协同 / 接力模式下，节点 `outputVariables` 包含最终输出变量和子智能体输出变量；意图分派模式下，下游只声明最终输出变量，避免引用未命中的子智能体变量。
- 集群输出模板只能引用本节点子智能体输出变量，用来组合多个子智能体结果；上游变量应在子智能体提示词或意图判断内容中使用。
- 子智能体引用的能力、提示词模板按当前编辑者重新校验。

运行态输出：

- 每个子智能体的输出变量，例如 `cluster_4_agent_1_output`。
- 可配置的最终输出变量，默认 `cluster_result`。
- `clusterAgents` 汇总列表。
- `final_answer` 集群汇总正文。
- `agent_response` 集群汇总正文。
- `summary` 执行摘要。

### 3.5 交付节点

节点类型：`delivery`

用途：生成 Word 文档、Excel 工作簿、发送邮件、调用 Webhook 或其他交付结果。

核心配置：

```json
{
  "brickType": "delivery",
  "deliveryMode": "capability",
  "deliveryCapabilityId": "00000000-0000-0000-0000-000000000613",
  "deliveryType": "word_document",
  "documentKind": "word",
  "markdownContent": "# 交付报告\n\n{{risk_summary}}",
  "fileNameTemplate": "交付文档-{{runNumber}}.docx"
}
```

约束：

- 工作流至少需要一个交付节点。
- 引用交付能力时必须在租户能力池中，且已分配给当前编辑者。
- 当前阶段交付节点通常位于线性链末尾。

## 4. 运行态状态机语义

### 4.1 状态说明

| 节点状态 | 说明 |
| --- | --- |
| `pending` | 等待执行 |
| `running` | 正在执行 |
| `waiting` | 等待用户输入或人工审核 |
| `completed` | 节点已完成，等待用户确认进入下一步 |
| `failed` | 节点执行失败，可恢复进度 |
| `canceled` | 用户主动中断，节点数据已清空，只能重新执行 |

### 4.2 操作矩阵

| 操作 | 适用状态 | 数据处理 | 子智能体结果 | 入口 |
| --- | --- | --- | --- | --- |
| 中断执行 | `running` | 节点置为 `canceled`，清空输出和变量快照 | 全部删除 | `interrupt` |
| 重新执行 | `canceled` / `failed` / `pending` / 已完成 AI 节点 | 整步从头重跑，清空输出、变量快照、追问历史 | 全部删除 | `restart` |
| 恢复进度 | `failed` / `pending` / 僵死 `running` | 保留可复用成功结果，只重跑失败或未完成部分 | 保留 `succeeded`，删除非成功 | `recover` |
| 追问 | 已完成且允许追问的 AI 节点 | 追加 `conversationHistory` 后续跑 | 集群只重跑被追问子智能体，顺序模式会清理后续子智能体 | `follow-up` |
| 回退 | 已保存任务中的已完成或失败节点 | 目标节点及后续节点重置为 `pending` | 全部删除 | `rollback` |

### 4.3 主动中断与恢复进度的边界

主动中断是用户明确放弃当前执行轮次。中断后：

- DB 作业会被终态化。
- Redis 取消信号会写入。
- 当前节点输出快照清空。
- 变量快照清空。
- 集群子智能体落库结果清空。
- 前端只展示“重新执行”。

恢复进度是系统异常或模型/MCP失败后的容错。恢复时：

- 不保留失败或未完成的子智能体。
- 保留已成功子智能体结果。
- 节点重新进入 `running`。
- 适合模型瞬时错误、Worker 断连、节点超时等被动失败。

本轮已明确：`recover` 不再接受 `canceled` 节点。若调用会返回 `WORKBENCH_NODE_RECOVER_INTERRUPTED`，提示使用重新执行。

## 5. 本轮修复清单

1. 已完成智能体集群节点支持“重新执行”，不会再因为 `parallel_group + completed` 返回 `WORKBENCH_NODE_RESTART_INVALID`。
2. 主动中断后的 `canceled` 节点调用 `recover` 会被拒绝，避免混淆“重新执行”和“恢复进度”。
3. 整步重新执行会清除单智能体顶层 `conversationHistory`，也会清除集群子智能体 `clusterAgents[].conversationHistory`。
4. 回退到某节点时，目标节点和后续节点都会清空输出、变量快照、子智能体落库结果和追问历史。
5. canceled 节点即使通过兜底推进路径执行，也按整步重启清理全部子智能体，不复用成功结果。
6. 前端新建多个输入节点时，默认输出变量不再重复。
7. 前端新建多个智能体集群节点时，默认子智能体输出变量不再重复。
8. 后端保存/发布校验新增输入节点字段变量一致性检查。
9. 后端保存/发布校验新增集群子智能体存在性、输出变量合法性和输出变量一致性检查。
10. 后端发布校验新增线性积木链约束，保证设计态连线与运行态顺序一致。

## 6. 验证范围

已执行：

```bash
./gradlew :apps:api:test \
  --tests com.agentum.workbench.application.WorkbenchRuntimeServiceTest \
  --tests com.agentum.workflow.application.WorkflowNodeConfigValidatorTest \
  --tests com.agentum.workflow.application.WorkflowPublishValidatorTest \
  --tests com.agentum.workflow.application.WorkflowDraftServicePublishTest

pnpm build:web
pnpm lint:web
git diff --check
```

验证结果：

- 后端定向测试通过。
- 前端构建通过。
- 前端 lint 通过。
- 空白检查通过。
- Vite 构建仍提示 vendor chunk 较大，这是既有构建体积提醒，不影响本轮功能正确性。

## 7. 后续建议

- 运行审计页接入后，应把 `restart`、`recover`、`interrupt`、`rollback` 的事件和变量快照差异做成只读链路，方便排查现场。
- 如果后续支持条件分支和并行图执行，需要先调整运行态执行器从 `sortOrder` 顺序推进改为图遍历，再放宽发布校验的线性链约束。
- 集群节点后续可增加显式汇总输出变量，例如 `cluster_result`，但必须同时让运行态真实写出该变量，不能只在设计态声明。
