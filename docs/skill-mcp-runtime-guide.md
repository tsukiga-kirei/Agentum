# Skill 与 MCP 运行机制

更新时间：2026-06-19

本文档以当前代码为准，说明 Skill 与 MCP 从系统登记、租户授权、流程配置到智能体调用和审计留痕的完整链路，并明确尚未实现的边界。

## 1. 核心区别

| 对比项 | Skill | MCP |
| --- | --- | --- |
| 当前定位 | 可由模型按需读取的方法说明和附加文本资源 | 可由模型调用的远程工具 |
| 给模型的工具 | `skill_<code>_read` | 每个远端工具对应一个 `mcp_<code>_<tool>_<index>` |
| 当前执行能力 | 读取 UTF-8 文本文件 | 发送 MCP `tools/call` |
| 是否执行 Skill 脚本 | **否** | 不适用，动作由 MCP Server 实现 |
| 适合场景 | 方法论、规则、分析步骤、使用说明 | 查询业务数据、调用系统接口、产生外部动作 |

需要“先理解业务方法，再查询或操作系统”时，推荐同时配置 Skill 和 MCP：Skill 说明何时、为何、如何使用，MCP 承担真实执行。

## 2. 共同的治理与冻结链路

```text
系统管理登记 system_capabilities
  -> tenant_capability_grants 放入租户能力池
  -> resource_grants 分配给用户 / 部门 / 角色
  -> 流程智能体节点保存 skillIds / mcpIds
  -> 发布时校验当前设计者是否仍可使用
  -> workflow_versions 冻结不可变配置快照
  -> 运行时再次校验能力状态、类型和租户授权
```

设计权限不会隐式向下传递能力权限。协作者即使能编辑流程，保存和发布时仍必须对所引用的 Skill/MCP 拥有使用权限；运行时也不会只相信发布快照，而会复核能力是否仍为 `active` 且租户授权是否仍为 `enabled`。

## 3. 智能体如何向模型提供工具

`AgentRuntimeService` 在每次初次执行或追问开始时：

1. 展开智能体模板和节点配置。
2. 调用 `SkillRuntimeService.resolveSkillTools`，并在每个模型推理回合前调用 `McpRuntimeService.resolveMcpTools` 实时发现 MCP 工具。
3. 根据本回合发现结果生成全部 Skill、MCP 工具定义，并追加平台工具 `final_answer`。
4. 每个正常模型轮次都发送当时最新的完整工具定义；模型选择工具后，执行结果以 `tool` message 追加到会话。
5. 模型调用 `final_answer` 后结束。如果达到节点配置的单轮最大推理次数，则执行一次不携带工具的最终汇总。

`maxAgentIterationsPerTurn` 必须由每个单智能体或集群子智能体显式保存。首次执行和每次追问都会重新进入一次 `executeAgentLoop`，所以计数按本轮对话重新从零开始，不累计上一轮次数。

流程设计目录接口同时下发：

- `suggestedIterationsPerTurn`：新节点表单建议值；
- `maxIterationsPerTurn`：平台允许的最大值。

两者分别由环境变量 `AGENTUM_AGENT_SUGGESTED_ITERATIONS_PER_TURN` 和 `AGENTUM_AGENT_MAX_ITERATIONS_PER_TURN` 调整。建议值只用于创建/补全设计态配置，不是运行时兜底；旧发布快照缺少字段时会明确报错，需重新保存并发布。

## 4. Skill 当前实现

### 4.1 登记与连通性测试

系统能力配置通过 `sourcePath` 指向 `SKILL.md`；历史 `manifestPath` 仍可用于定位同目录文件。`FilesystemSkillManifestProbe` 会读取：

- `SKILL.md` 的 YAML frontmatter；
- 同目录 `skill.yaml` 的名称、描述、版本和 inputs；
- 将 inputs 转成预览用 JSON Schema。

探测只验证文件与元数据，不执行文件中的命令或脚本。

### 4.2 运行时读取

每个 Skill 被转换为一个 `skill_<code>_read` 工具。模型参数可传 `filePath`（兼容 `file_path`），未传时读取 `SKILL.md`。

安全限制：

- 仅允许 Skill 目录内相对路径；
- 禁止绝对路径和包含 `..` 的路径；
- 归一化后的目标必须仍位于 `SKILL.md` 同目录；
- 文件必须存在且为普通文件；
- 使用 UTF-8 读取；
- 单次最多回传 12,000 个字符，超出后追加截断标记。

观察结果包含 `skillCode`、`skillName`、`filePath`、`content` 和 `truncated`。运行页会展示读取摘要和可展开详情；流式事件为控制前端负载会限制展示长度，模型实际获得的内容仍受 12,000 字符上限控制。

### 4.3 Skill 脚本是否会执行

当前**没有实现 Skill 脚本执行**。即使 `SKILL.md` 或 `skill.yaml` 提到了 Python、Node.js、Shell 脚本，Agentum 目前也只把这些内容作为文本提供给模型，不会启动进程。

当前不存在以下能力：

- Skill entrypoint/runtime 协议；
- Shell、Python 或 Node.js 执行器；
- 容器/沙箱、文件和网络权限白名单；
- 脚本超时、资源限额和人工审批；
- 脚本产物协议及独立执行审计。

因此，需要真实查询、计算或系统操作的能力，应先实现为 MCP Server，再用 Skill 描述使用方法。未来若引入脚本执行，必须先定义声明式入口、隔离 Worker、最小权限、超时和资源配额、审批、产物及审计契约，不能直接在 API 进程内执行任意脚本。

## 5. MCP 当前实现

### 5.1 系统管理测试与工具发现

系统管理根据 `transport` 路由：

- `sse`：连接 SSE 流，读取服务端提供的消息 POST endpoint；
- `streamable_http`：在同一 HTTP endpoint 上发送 JSON-RPC POST。

连接测试按 MCP 协议执行 `initialize`、`notifications/initialized`、`tools/list`。测试成功后把发现到的工具 `name`、`description`、`inputSchema` 持久化到系统能力的 `config.tools`，供系统管理预览、人工核对和故障诊断使用。远端 MCP 契约不受 Agentum 版本控制，因此运行时不把该快照作为工具事实源。

浏览器直接 GET Streamable HTTP `/mcp` 不能代替连接测试；无状态服务返回“不支持 GET”是正常协议行为。真正调用必须是带正确 `Accept` 和 JSON-RPC body 的 POST。

### 5.2 工具定义如何给 AI

运行时按节点的 `mcpIds` 读取能力，并在每个模型推理回合前重新执行 `initialize`、`notifications/initialized`、`tools/list`：

- 默认将本回合实时发现的每个远端工具分别暴露给模型，完全忽略管理侧 `config.tools` 预览快照；
- 如果节点或能力显式配置 `toolName` / `mcpToolName` / `defaultToolName`，只暴露当前列表中的该工具；工具已不存在时返回 `MCP_CONFIGURED_TOOL_NOT_FOUND`，不猜测重命名关系；
- 当前 `tools/list` 为空时返回 `MCP_TOOL_LIST_EMPTY`，不拿能力编码或历史工具名冒充远端工具；
- 发现工具的 `inputSchema` 原样成为模型 Function Calling 参数 Schema，因此 MCP Server 提供的名称、描述、参数类型和 required 字段会直接影响模型能否正确调用；
- 某次 `tools/call` 因工具删除、参数变化或网络故障失败时，失败作为 observation 回写模型；下一推理回合会再次发现工具，让模型基于最新契约重新规划。

平台生成唯一 function name 供模型使用，同时在 binding 中保留真实远端工具名；发送 `tools/call` 时使用后者。

### 5.3 实际调用协议

SSE 调用链：

```text
GET SSE URL
  -> 接收 endpoint 事件
  -> POST initialize
  -> POST notifications/initialized
  -> POST tools/call
  -> 从 SSE message 事件读取对应 JSON-RPC id 的结果
```

Streamable HTTP 调用链：

```text
POST initialize
  -> POST notifications/initialized
  -> POST tools/call
  -> 按 Content-Type 解析 application/json 或 text/event-stream
```

当前协议版本为 `2024-11-05`，连接超时 3 秒，单次协议操作超时 15 秒。Streamable HTTP 请求同时声明 `Accept: application/json, text/event-stream`，包括无返回值通知，避免严格服务端返回 406。

工具结果保留原始结果对象，并汇总 `content[].text` 到 `text` 字段。MCP 可能返回 HTTP 200 但 `isError=true`；此时 Agentum 仍按工具执行失败处理。

### 5.4 失败是否允许 AI 继续回答

失败分两类：

| 类型 | 示例 | 行为 |
| --- | --- | --- |
| 可恢复的外部执行失败 | `MCP_TOOL_EXECUTION_FAILED`、`MCP_CALL_FAILED` | 作为 `isError` observation 回写模型，模型可以换工具或解释数据不可用，然后继续回答 |
| 配置、授权或治理失败 | 能力不存在、未启用、未授权、当前工具列表为空、显式指定工具已不存在 | 终止节点，避免绕过安全边界或在错误配置上继续运行 |

因此，“中台接口返回业务失败”不应必然让智能体节点直接失败；只要被归类为上述可恢复错误，模型会再获得一次响应机会。若连模型继续回答也没有发生，应先看错误码是否属于配置/权限类，或是否在进入 MCP 工具执行前已经失败。

### 5.5 日志、审计与脱敏

每次真实 MCP 调用都会写入 `mcp_call_logs`，关联 tenant、run、node run、workflow、version、capability 和 tool，记录脱敏后的请求/响应、状态、错误码、错误信息和耗时。

请求与响应会递归检查字段名，命中以下敏感特征时替换为 `***`：

`password`、`token`、`secret`、`apikey`、`api_key`、`authorization`、`credential`、`cookie`、`privatekey`、`private_key`。

这是一种按字段名的结构化脱敏，不会智能识别藏在普通文本字段中的凭证明文。因此 MCP Schema 和返回结构也应避免把凭证塞进 `text`、`message` 等普通字段。

## 6. 当前协议边界

- SSE 与 Streamable HTTP 客户端在每次运行时工具发现和每次工具调用时都会重新初始化；Streamable HTTP 当前没有维护服务端返回的 `Mcp-Session-Id`，适合无状态 MCP Server。若服务端要求会话状态或依赖 `notifications/tools/list_changed`，需要后续补运行轮次级会话与通知监听；当前通过每个模型推理回合主动 `tools/list` 保证最终一致。
- 当前系统能力配置只保留 transport、endpoint 和 tools，没有通用自定义 Header/凭证注入机制。需要鉴权的 MCP 暂不能靠登记 Token 直接接入，建议先通过受控网关或后续凭证中心方案实现，禁止把 Token 写进 URL。
- 当前没有高风险 MCP 工具的人工审批卡点。
- Skill 没有脚本执行器；MCP 是当前真实外部动作的标准入口。

## 7. 排查顺序

### 7.1 工具没有出现在 AI 请求中

1. 检查流程发布快照的 `skillIds` / `mcpIds`。
2. 检查能力是否 `active`，租户授权是否 `enabled`。
3. 查看运行日志中的“MCP 运行时工具发现”，确认本轮 `tools/list` 不为空且 Schema 正确；系统管理测试只用于独立连通性诊断。
4. 查看 `AI调用链路-请求` 的 tools 快照，确认本回合实时发现的工具定义是否真正发给模型。

### 7.2 MCP Server 没有请求日志

如果 Agentum 已报 “Tool not found” 且 MCP Server 没有日志，通常表示调用尚未发到服务端：模型调用了平台函数名或旧能力编码，但运行时找不到对应 binding。若日志出现 DNS、连接超时或 406，则请求已进入协议客户端；再根据 endpoint、`Accept`、网络解析和服务端实现排查。

### 7.3 参数不正确

优先修正 MCP Server 的 `tools/list.inputSchema` 和 description。Agentum 会把它们直接交给 AI；不应只在 Prompt 里口头描述 required 参数。调用后可对照 `model_call_logs` 中的 tool arguments 与 `mcp_call_logs.request_payload`。

## 8. 关键代码索引

| 主题 | 路径 |
| --- | --- |
| Agent 工具循环 | `apps/api/src/main/java/com/agentum/agent/application/AgentRuntimeService.java` |
| 单轮次数配置 | `apps/api/src/main/java/com/agentum/agent/application/AgentRuntimeProperties.java` |
| Skill 运行时 | `apps/api/src/main/java/com/agentum/agent/application/SkillRuntimeService.java` |
| Skill 源文件探测 | `apps/api/src/main/java/com/agentum/system/infrastructure/FilesystemSkillManifestProbe.java` |
| MCP 运行时治理 | `apps/api/src/main/java/com/agentum/mcp/application/McpRuntimeService.java` |
| MCP 传输路由 | `apps/api/src/main/java/com/agentum/mcp/infrastructure/McpRuntimeClientRouter.java` |
| SSE 客户端 | `apps/api/src/main/java/com/agentum/mcp/infrastructure/HttpMcpSseRuntimeClient.java` |
| Streamable HTTP 客户端 | `apps/api/src/main/java/com/agentum/mcp/infrastructure/HttpMcpStreamableHttpRuntimeClient.java` |
| 系统连接测试 | `apps/api/src/main/java/com/agentum/system/infrastructure/HttpMcpSseConnectionTester.java`、`HttpMcpStreamableHttpConnectionTester.java` |
| 流程发布校验 | `apps/api/src/main/java/com/agentum/workflow/application/WorkflowNodeConfigValidator.java` |
| 流程设计器 | `apps/web/src/surfaces/designer/WorkflowEditorPage.tsx` |
