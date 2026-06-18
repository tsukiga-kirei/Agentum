# 架构文档

## 1. 架构目标

Agentum 的架构目标是支撑长期演进的企业级智能体工作流，而不是只做一个可演示编排器。

核心要求：

- 多租户、用户、部门、角色和权限从第一版内建。
- 工作流定义和运行实例分离，发布版本可回放。
- 智能体模板、Skills、MCP、提示词模板、模型配置和交付能力作为资产独立治理。
- 第一阶段去掉知识库核心链路，避免过早引入 RAG 复杂度。
- 所有 MCP、模型、交付和凭证操作都经过后端统一网关。
- 用户输入、智能体追问、人工审核、外部回调和高风险审批统一建模为暂停与恢复。
- 数据库结构随代码通过 Flyway 迁移同步更新。

## 2. 推荐技术栈

| 层 | 技术 |
| --- | --- |
| 前端 | React、TypeScript、Vite、Tailwind CSS、Ant Design、lucide-react、Zustand |
| API | Java 21、Spring Boot、Spring Security、Spring Data JPA |
| 数据库 | PostgreSQL |
| 数据库版本 | Flyway |
| 缓存 | Redis |
| 队列 | RabbitMQ |
| 文件 | MinIO / S3 兼容对象存储 |
| 契约 | OpenAPI、JSON Schema |
| Worker | Java Worker 优先，复杂文档和 AI 辅助任务可使用 Python Worker |

当前推荐保持后端 Java / Spring Boot。Agentum 的核心风险在权限、审计、状态机、事务一致性和企业系统集成，这些能力更适合稳定的企业后端栈。

## 3. 总体架构

```text
Web 前端
  |
  | REST / OpenAPI
  v
API 服务
  |
  |-- 认证与会话
  |-- 租户、组织、部门、用户、角色
  |-- 权限策略、资源范围与能力池分配
  |-- 工作流定义与发布
  |-- 工作流运行状态机
  |-- 智能体运行时
  |-- 能力资产管理
  |-- MCP 网关
  |-- 模型供应商网关
  |-- 交付模块
  |-- 审计日志
  |-- 系统管理
  |
  v
PostgreSQL / Redis / RabbitMQ / MinIO
  |
  v
Worker
  |
  |-- 模型调用
  |-- 文件解析
  |-- 文档生成
  |-- 外部系统推送
  |-- 长耗时交付任务
```

第一阶段保持单体 API 服务，但内部包边界必须清楚。Worker 可以先占位，等文档生成、外部交付和长耗时模型调用变复杂后再拆。

### 3.1 能力源码与治理边界

Agentum 需要同时管理“能力资产记录”和“能力可执行实现”。资产记录解决资产是什么、版本是什么、是否进入租户可用能力池、分配给哪些用户 / 部门 / 角色；可执行实现解决 Skill、MCP Server 和交付适配器到底如何开发、测试、部署。提示词模板仍作为资产记录和前端配置保留，当前不再维护独立源码目录。

推荐仓库目录：

```text
capabilities/
  skills/
    README.md
    <skill-key>/
      SKILL.md
      skill.yaml
      examples/
      tests/
  mcp-servers/
    README.md
    <server-key>/
      manifest.yaml
      src/
      tests/
      README.md
  delivery/
    README.md
    <delivery-key>/
        src/
        tests/
```

边界规则：

- `apps/api` 是控制面和治理面，负责能力登记、租户可用能力池、租户内能力分配、凭证注入、调用审计和版本发布。
- `capabilities/skills/` 保存产品运行时 Skill 源文件，发布后在数据库 `skills` 或资产表里形成版本记录。
- `capabilities/mcp-servers/` 保存自研 MCP Server 源码。每个 Server 应能独立启动、测试和部署，API 的 MCP 网关只通过注册信息调用它，不把具体业务连接逻辑写进网关。
- `capabilities/delivery/` 只保存自定义交付适配器的 Manifest、源码和本地验证材料，发布后再登记为交付能力资产；系统内置交付能力（如邮箱、Word 文档生成）由 API / Worker 原生实现，不放入该目录。
- 提示词模板功能保留在能力资产、系统管理登记和前端配置链路中；当前不再维护独立的提示词模板源码目录。
- `packages/shared-contract` 保存 MCP、Skill、智能体、工作流和事件的共享 Schema，不保存具体能力实现。
- 数据库中的 `skills`、`mcp_services`、`prompt_templates`、`delivery_capabilities` 更像资产注册表，不应直接承担源码仓库职责。
- `.codex/skills/` 是本仓库开发辅助技能目录，不进入 Agentum 产品运行时能力发布链路。

自研 MCP Server 的推荐接入链路：

```text
capabilities/mcp-servers/<server-key>
  -> 本地测试和 manifest 校验
  -> 系统管理员登记为全局 MCP
  -> 放入租户可用能力池
  -> 租户管理员在当前租户内分配给用户、部门或角色
  -> 用户在能力资产、智能体模板和流程设计中自然可见并可用
  -> 运行时由 MCP 网关统一注入凭证、执行安全策略、脱敏和审计
```

## 4. 前端边界

前端负责：

- 登录页、工作台壳层和角色入口展示。
- 业务工作台：待办、可用流程、业务运行详情、运行态摘要、交付物。
- 流程设计：工作流列表、阶段积木编排、节点配置、变量面板。
- 能力资产：智能体模板、Skills、MCP、提示词模板、交付能力。
- 运行监控：运行状态、失败重试、取消、补偿和管理员介入入口。
- 运行审计：只读执行链路、变量快照、工具调用、审核记录、交付记录和证据链。
- 租户管理：当前租户内用户、部门、角色、资源范围、能力池分配、需求配置、运行安全策略和审计可见性配置入口。
- 系统管理：租户、模型、全局能力、交付通道和凭证策略配置入口。

组件策略：

- Tailwind CSS 和本地 CSS 变量负责页面布局、工作台信息层级、主题色和 Agentum 自身视觉语言。
- Ant Design 负责复杂交互控件，例如选择器、表单校验、表格、弹窗、树、日期选择、上传和权限配置类组件。
- lucide-react 继续作为轻量图标来源，避免为图标单独绑定组件库风格。
- 引入大型组件库后，前端构建应通过 Vite 分包拆出 UI 框架和主要 vendor chunk，避免主入口包持续膨胀。

前端不负责：

- 认证最终判断。
- 租户、角色、资源范围和能力池分配最终判断。
- MCP 凭证明文读取。
- 模型调用。
- 工作流真实执行。
- 高风险交付审批最终判断。
- 审计页中的业务恢复动作。

前端页面是视图组合层，不是数据模型边界。页面可以为了角色任务聚合多个后端对象，也可以随着产品理解加深继续拆分、合并或改名。

实现约束：

- 页面不能反向要求后端把聚合视图落成同名大表。
- 当前静态卡片和说明面板只用于早期表达信息层级，不代表生产最终交互。生产页面应沉淀为左侧大菜单、模块内页签 / 顶层菜单、列表、详情、表单、授权矩阵和审计记录，并由真实 API 数据驱动。
- 左侧菜单表示模块级权限，模块内页签表示当前模块的详细功能区；租户管理里的人员组织、角色与入口、能力分配、资源范围、运行安全策略和租户内审计不应全部扩散成全局顶层菜单。
- 权限分两重：第一重是 `business`、`tenant_admin`、`system_admin` 三大登录入口；第二重是租户管理员按用户、部门、租户自定义角色配置的模块、页签、资源范围和系统能力可用能力池。用户自建能力和流程在资源自身再维护读取 / 使用与内容编辑权限，均支持 `self`、`specified`、`all`。
- 页面之间必须能互相勾稽：系统管理的租户可用能力池决定租户管理可分配的能力；租户管理的角色、资源范围和能力池分配决定业务工作台、能力资产和流程设计可见内容；流程设计引用的资产版本必须能在运行审计中追溯。
- 运行审计里的“执行链路”应由 `WorkflowRun`、`NodeRun`、`WaitingEvent`、`RunEvent` / `audit_logs`、`mcp_call_logs`、`delivery_records` 和 `variable_snapshots` 聚合生成。
- 审计日志只记录可追溯事件，不承担所有运行状态、变量快照和交付记录的存储职责。
- 能力资产页的创建、发布、停用、版本和引用关系应归属各资产模块；泛化的创建入口只适合早期占位，后续应替换为治理概览或引用关系视图。

## 5. 后端模块

推荐包边界：

```text
com.agentum
  auth
  tenant
  organization
  permission
  workflow
  agent
  asset
  mcp
  modelprovider
  delivery
  audit
  system
  shared
```

### 5.1 认证与租户上下文

认证模块负责登录、登出、会话刷新、账号状态、角色切换和当前用户信息。

请求进入业务接口后，应构造统一上下文：

```text
userId
tenantId
departmentIds
roleIds
requestId
isSystemAdmin
```

登录阶段就应确定活跃角色和租户上下文。业务用户和租户管理员登录时必须携带 `tenantId` 与期望入口角色，后端校验租户状态、成员关系和入口角色后再签发令牌。审核人、流程设计者、能力管理员等属于租户内自定义角色或权限分组，不作为登录页入口角色。系统管理员登录不要求 `tenantId`，其平台级接口通过系统管理员身份访问；当系统管理员操作某个租户资源时，再通过明确参数或页面选择指定目标租户。第一阶段保留业务用户、租户管理、系统管理三类基础入口，租户内管理职责统一落在“租户管理”入口。

推荐公开一个轻量租户列表接口供内网登录页使用，只返回活跃租户的 `id`、`name` 和 `code`。如果后续改成公网 SaaS，应改为租户编码、企业邮箱域名或子域名识别，避免公开枚举租户名称。

必须参照 AuraOA 的权限分层思路：公开登录接口、已登录接口、租户内管理接口、系统管理员接口分组；JWT 解析后注入租户上下文，再做角色校验。Agentum 的命名与 AuraOA 不完全一致，AuraOA 的租户管理员能力在本项目第一阶段落到租户管理入口。

角色 / 租户切换必须作为认证与上下文能力设计。一个用户可同时拥有业务用户、租户管理员、系统管理员等登录入口角色；租户内自定义角色属于第二重权限，不作为登录入口。切换时前端更新活跃角色、租户、左侧菜单、模块内页签、资源范围和可用能力池，后端仍要按 token、tenantId、roleIds、departmentIds、人员分配、资源范围和运行安全策略复核每个请求。

企业 SSO 按“外部身份认证来源”接入，不替代 Agentum 本地权限模型。当前优先支持租户级 OIDC：业务系统或身份平台只提供标准 OIDC Provider，Agentum 负责生成授权跳转、校验回调、绑定外部 `sub` 与本地用户，并重新计算 `user_role_assignments`、菜单和资源权限。对接细节维护在 [企业 SSO 对接说明](./sso-integration.md)。

### 5.2 用户、部门、角色

职责：

- 租户成员管理。
- 部门树管理。
- 角色管理。
- 用户角色分配。
- 账号状态管理。
- 邀请、禁用、锁定和密码策略占位。

第一阶段至少需要能描述“谁属于哪个租户 / 部门，以什么角色操作哪些资源”。

### 5.3 权限模块

权限模块采用 RBAC + 模块入口 + 资源范围 + 能力池分配 + 运行安全策略。

权限配置必须支持租户自定义角色、部门、人员三个维度：角色给默认入口和能力池，部门限制组织范围，人员级分配只用于特殊补充并必须写入审计。租户管理员可以分配租户内菜单、模块内页签、资源范围、可用能力池和运行安全策略；系统管理员只负责平台级能力登记测试、租户可用能力池和模型分配，不直接代替租户管理员维护日常业务权限。

判断顺序：

1. 用户是否登录。
2. 是否有租户或系统管理上下文。
3. 是否属于目标资源范围。
4. 角色或人员分配是否允许进入目标模块 / 页签。
5. 资源范围是否允许访问具体对象。
6. 若涉及能力资产，该能力是否已进入当前主体的可用能力池。
7. 是否触发运行安全策略。
8. 是否需要审批或二次确认。
9. 写入审计日志。

### 5.4 工作流定义模块

职责：

- 保存工作流草稿。
- 工作流定义维护 `read_scope`、`edit_scope` 和指定成员授权明细；编辑自动包含读取，创建者始终拥有全部权限且独占权限配置能力。
- 协作者保存、校验和发布流程时，按当前操作者重新校验节点引用的系统能力分配与用户自建能力读取权限，流程编辑权限不向下传递引用能力权限。
- 保存固定节点类型配置。
- 管理步骤积木、节点依赖和必要分支。
- 校验变量引用。
- 发布不可变版本。

定义态对象：

- `WorkflowDefinition`
- `WorkflowVersion`
- `WorkflowNodeDefinition`
- `WorkflowEdgeDefinition`
- `WorkflowVariableDefinition`

设计态应优先保存左侧步骤积木与右侧配置。第一阶段可选积木收敛为输入节点、单智能体节点、智能体集群节点和交付节点；节点顺序、折叠状态和 UI 选中态只能放在 `layout` 或 `ui_schema`，不能污染执行器协议；自由画布坐标不作为主交互假设。

### 5.5 工作流运行模块

职责：

- 创建 `WorkflowRun`。
- 按版本生成执行计划。
- 执行节点状态机。
- 管理变量快照。
- 处理暂停、恢复、回退、取消、重试。
- 调度并行节点和合并节点。
- 写入节点运行记录和审计事件。

运行态对象：

- `WorkflowRun`
- `NodeRun`
- `VariableSnapshot`
- `WaitingEvent`
- `RunEvent`
- `ModelCallLog`
- `McpCallLog`
- `DeliveryRecord`

状态建议：

```text
pending -> running -> paused -> resumed -> running -> completed
                         |                    |
                         v                    v
                      canceled              failed
```

业务恢复、管理员介入和审计查看应分层处理：

- 业务恢复：用户输入、人工审核、追问确认、高风险审批和高风险交付确认，来自待办详情或业务运行详情；当前已落地用户输入和人工审核恢复。
- 管理员介入：取消、重试、补偿和故障处理，来自运行监控。
- 审计查看：执行链路、节点输入输出快照、工具调用、审核和交付记录，只读展示，不修改运行状态。

**异步执行（已落地，仅 async 模式）**

运行态已重构为「RabbitMQ 执行 + Redis Stream 进度回放」：`POST /advance` 仅创建执行作业（`workflow_run_execution_jobs`）并投递 `NodeExecuteCommand`，同 JVM 的 `NodeExecutionService` Worker 消费执行；执行租约、取消信号、节点超时（`AGENTUM_RUNTIME_NODE_TIMEOUT_SECONDS`）与进度事件均放 Redis，PostgreSQL 仍为事实源。SSE 由 `RunStreamRelayService` 从 Redis Stream 中继，支持 `lastEventId` 断线续传与 `replay` 整步回放，刷新/重进页面无感恢复。`StaleExecutionReaper` 定时回收超时与失联作业。原 `@Async` + 内存 SSE 路径已删除，后端强依赖 Redis 与 RabbitMQ。中断/恢复语义：主动中断 → 节点 `canceled` 并清空该步数据，只能「重新执行」整步重跑；被动失败 → 节点 `failed`，已落库的子智能体结果（`workflow_cluster_agent_runs`）保留，「恢复进度」只重跑失败/未完成部分。智能体集群节点支持 `parallel`（真并发）与 `sequential`（顺序）两种执行方式。详见 [运行态异步执行设计](./runtime-async-execution-design.md)。

### 5.6 智能体运行时

智能体运行时不是一次模型调用，而是一条可审计管线。

```text
变量准备
  -> 资产解析
  -> 权限校验
  -> Skills 装配
  -> MCP 工具声明
  -> Prompt 组装
  -> 模型调用
  -> 工具调用
  -> 输出校验
  -> 暂停或继续
  -> 变量落库
  -> 审计记录
```

第一阶段暂不加入知识库检索步骤。

运行时对象：

- `AgentRun`
- `AgentMessage`
- `AgentToolCall`
- `AgentSkillSnapshot`
- `AgentOutputValidation`
- `AgentInteractionEvent`

这些对象可以第一阶段作为 `NodeRun` 扩展表或 JSON 字段，后续再拆独立表。

### 5.7 能力资产模块

职责：

- 智能体模板管理。
- Skills 管理。
- MCP 管理。
- 提示词模板管理。
- 交付能力资产管理。
- 版本、发布状态和引用关系。

资产只做“能力是什么”的管理。租户级可用能力池、租户内分配、凭证策略和运行安全策略由系统管理与权限模块负责。

能力资产页应提供新增、发布、停用、版本管理和引用关系查询。流程节点如何选择和引用资产属于流程设计的节点配置，不应把“流程导入建议”作为能力资产页主内容。

资产管理的界面抽象不应产生额外资源层级。每类资产独立维护创建入口和生命周期；如果页面需要右侧辅助区，优先展示引用概览、能力池缺口、高风险待审批、草稿待发布和版本漂移等治理信息。

“我的能力”采用草稿发布模型。业务用户只能创建 `prompt_template` 和 `agent_template` 两类租户内草稿，并独立维护 `read_scope`、`edit_scope` 与指定成员授权明细。编辑权限自动包含读取权限，创建者独占权限配置和删除能力；被授予编辑权限的同事可以持续维护内容与生命周期。`systemPromptTemplateId` 只能引用已发布且当前操作者可读取的提示词模板；`skillIds` / `mcpIds` 必须引用当前主体已开放的系统能力。租户内相互引用只允许指向已发布版本，发布与保存时再次校验租户能力池、主体分配与用户自建能力读取权限，防止权限变化或手写 ID 绕过安全边界。Skill、MCP 和交付能力仍属于系统管理登记和运行网关治理范围。

数据库上 `tenant_asset_capabilities` 先承接草稿与发布外壳，`config` 保存类型相关配置，并通过 Flyway 逐步增加发布状态和约束。删除能力时当前先校验创建者边界；后续必须补资产引用索引，凡是被工作流草稿、发布版本、运行快照或审计证据链引用的能力都不能物理删除。后续如果智能体模板、提示词模板出现复杂版本比较、引用关系或审核流，再从该表拆分到专门资产表，但运行时引用仍应指向不可变发布版本。

### 5.8 MCP 网关

职责：

- 注册 MCP 服务。
- 同步工具列表。
- 校验参数 Schema。
- 校验用户、租户、角色、智能体、工作流、可用能力池和运行安全策略。
- 注入服务端凭证。
- 限流。
- 脱敏结果。
- 写入审计。

模型不能看到所有工具，只能看到当前节点和当前用户可用能力池中的工具。

当前运行态已支持标准 MCP SSE 与 Streamable HTTP 接入：系统管理登记端点并测试连接后，平台持久化 `tools/list` 返回的工具名、描述和 `inputSchema` 契约快照；运行节点按租户能力池授权复核，把每个远程工具分别声明给模型，再通过 `initialize` / `notifications/initialized` / `tools/call` 执行。工具名、脱敏参数、结果、耗时和失败原因写入 `mcp_call_logs`，MCP 返回的 `isError=true` 必须按失败记录，不能因 HTTP 请求成功而误记为工具成功。外部工具执行或网络故障属于可恢复 observation，允许模型改用其他工具或解释数据缺失；能力未授权、配置无效等安全与治理错误仍终止节点。

智能体节点的 MCP 由 `AgentRuntimeService` 在 ReAct 循环中处理：`McpRuntimeService.resolveMcpTools` 把当前节点可用 MCP 转成模型工具声明，模型选择具体工具后执行 `McpRuntimeService.executeResolvedTool`，观察结果作为 tool message 回写到下一轮模型推理。最终输出必须通过 `final_answer` 工具提交。

### 5.9 模型供应商模块

职责：

- 注册模型供应商。
- 管理模型名称、上下文长度、默认参数和可用租户。
- 测试模型连接。
- 统计调用成本和 Token 用量。
- 为租户配置默认模型或允许模型集合。

模型密钥必须服务端加密存储，前端只展示脱敏状态。

当前运行态按 `tenant_model_assignments` 选择租户启用模型，解密模型供应商 API Key 后调用 OpenAI 兼容 / 通义兼容 / Azure OpenAI Chat Completions。聊天客户端支持 Function Calling 工具声明与 SSE 文本流，把每轮提示词摘要、响应摘要、Token 用量、耗时和失败原因写入 `model_call_logs`。Anthropic Messages 协议暂未接入，运行时会返回明确错误而不是生成占位输出。

### 5.10 交付模块

职责：

- 读取变量和模板。
- 生成 Word / PDF。
- 发送邮件。
- 创建 OA 流程。
- 推送 IM。
- 调用 Webhook。
- 写入数据库。
- 记录交付结果和失败重试。

交付能力分为系统内置和自定义适配器。系统内置交付能力由 API / Worker 原生实现，例如邮箱发送和 Word 文档生成；自定义交付适配器放在 `capabilities/delivery/<delivery-key>/`，通过 Manifest 声明 `runtime`、`entry`、`configSchema`、`inputSchema`、`outputSchema` 和风险等级。高风险交付能力必须走权限校验、审批或二次确认。

当前运行态已支持三类能力化交付：系统内置邮箱发送、Webhook 调用和 Word 文档生成。Word 初版由 API 内轻量渲染器把 AI Markdown 转为 `.docx`，文件写入 MinIO/S3 兼容对象存储，并通过 `delivery_records` 记录下载入口；复杂模板、图片、目录、页眉页脚和大文档后续应迁移到 Worker。所有交付动作都会写入 `delivery_records`，并关联租户、运行、节点、流程定义和发布版本；失败时节点和运行进入失败状态。

### 5.11 系统管理模块

系统管理负责平台级配置：

- 租户管理。
- 全局模型供应商。
- 全局 MCP。
- 全局 Skills 和提示词模板。
- 全局交付能力。
- 租户可用能力池与模型分配。
- 系统参数。
- 数据保留策略。
- 凭证安全策略。

系统管理员在系统管理的租户抽屉中完成“租户可用能力池 / 模型分配”后，租户管理员再在当前租户内分配给用户、部门或角色。能力被分配后，用户在能力资产、智能体模板和流程设计中自然可见并可用。

凭证策略、模型密钥和全局 MCP 凭证由系统管理维护；权限模块只判断能力是否进入当前主体的可用能力池，以及高风险调用是否触发申请、审批、额度、脱敏或审计策略。

系统管理前端不应长期停留在单页卡片展示。生产结构应在“系统管理”左侧大模块内包含平台概览、租户管理、能力管理和模型管理等页签 / 顶层菜单；租户可用能力池与模型分配收敛到单租户抽屉，概览卡片只展示统计和风险入口，具体操作必须进入列表、详情和表单。

### 5.12 租户管理模块

租户管理是当前租户内的管理入口，承担 AuraOA 中租户管理员一类能力在 Agentum 内的落点。

职责：

- 管理当前租户内用户、部门、角色和成员关系。
- 配置角色入口、资源范围、能力池分配、运行安全策略和审计可见性。
- 配置租户内需求规则，例如表单字段、审核规则、交付目标、可用能力和流程发起约束。
- 将系统管理放入租户可用能力池的 MCP、Skills、提示词模板、交付能力，以及分配给租户的模型供应商继续分配给用户、部门或角色。

租户管理不能创建或启停租户，不能读取系统级模型密钥和全局 MCP 凭证明文，不能越过系统管理配置的租户可用能力池。

租户管理生产页面同样不应依赖卡片跳转。左侧菜单只负责进入“租户管理”大模块，模块内再用人员组织、角色与入口、能力分配、资源范围、需求配置、运行安全策略和租户内审计等页签区分功能；角色、部门、人员级分配决定用户能看到哪些页签、资源和能力池。

### 5.13 分页与列表查询组件

管理台（系统管理、租户管理、能力资产、审计）的一切列表接口应统一分页约定，并下沉到后端 `shared` 组件，避免各模块重复实现。

建议在 `com.agentum.shared` 下提供：

- `pagination/PageQuery`：承载 `page`、`size`、`sort` 并做边界校验（例如 page>=1、size 上限）。
- `pagination/PageResponse<T>`：统一返回 `items`、`page`、`size`、`total`、`totalPages`。
- `pagination/PageableFactory`：把 `PageQuery` 转为 Spring `Pageable`，同时做排序字段白名单映射。
- `pagination/SortWhitelist`（可选）：按模块声明允许排序字段，防止前端任意字段排序导致 SQL 风险或性能抖动。

接口建议：

```text
GET /api/.../xxx?page=1&size=20&sort=createdAt,desc
```

返回建议：

```json
{
  "success": true,
  "data": {
    "items": [],
    "page": 1,
    "size": 20,
    "total": 0,
    "totalPages": 0
  },
  "error": null,
  "requestId": "req_xxx"
}
```

实施规则：

- 新增管理台列表接口时必须复用 `shared.pagination` 组件，不允许模块内各自定义分页 DTO。
- OpenAPI 需同步分页参数与分页响应模型，前端按契约驱动分页组件。
- 分页查询应与筛选条件同层声明（如 `keyword`、`status`、`tenantId`），避免把筛选塞入不透明 JSON。

## 6. 数据模型建议

第一阶段优先建立以下表族：

| 表族 | 说明 |
| --- | --- |
| 租户与人员 | `tenants`、`users`、`departments`、`roles`、`user_memberships` |
| 权限 | `permission_policies`、`resource_scopes`、`runtime_security_policies` |
| 系统能力 | `model_providers`、`system_capabilities`、`tenant_capability_grants`、`subject_capability_assignments` |
| 能力资产 | `agent_templates`、`skills`、`mcp_services`、`prompt_templates`、`delivery_capabilities` |
| 工作流定义 | `workflow_definitions`、`workflow_versions`、`workflow_nodes`、`workflow_edges` |
| 工作流运行 | `workflow_runs`、`workflow_node_runs`、`variable_snapshots`、`workflow_waiting_events`、`workflow_run_events` |
| 审计与交付 | `audit_logs`、`model_call_logs`、`mcp_call_logs`、`delivery_records` |

数据库字段必须包含 `created_at`、`updated_at`，重要业务表建议包含 `tenant_id`、`created_by`、`updated_by`。

资源范围要覆盖工作流定义、智能体模板、运行记录、变量快照、交付物和审计日志。一个智能体或交付物完成后，只允许分配范围内的用户、部门或角色查看；敏感文件下载和审计日志查看属于运行安全策略，应单独校验和审计。系统能力走可用能力池分配；用户自建能力和流程在资源自身维护读取 / 使用与内容编辑权限。

## 7. 数据库版本管理

数据库使用 PostgreSQL，迁移使用 Flyway。

规则：

- 迁移文件放在 `apps/api/src/main/resources/db/migration/`，按用途拆为 `schema/` 和 `devdata/`。
- `schema/` 只放真实表结构、索引、约束和结构性数据迁移；`devdata/` 只放本地开发账号、演示租户、演示能力和开发期兼容清理。
- 文件命名使用 `VyyyyMMddHHmm__description.sql` 或项目统一递增版本。
- Flyway 版本号在两个目录之间必须全局唯一，本地 profile 同时扫描 `schema` 和 `devdata`。
- 每次数据库结构变更必须随代码提交迁移脚本。
- 不允许只修改本地数据库而不提交迁移。
- 表和关键字段必须写中文注释。
- 当前仍处开发阶段，数据库模型优先服务功能清晰和长期可维护；确认引用关系和迁移策略后，可以删除不再需要的表和字段，不为早期本地演示数据长期保留兼容包袱。

默认配置只扫描 `schema`，本地启动 API 时 `local` profile 会同时扫描 `schema` 和 `devdata` 并自动执行迁移。生产环境部署前要先备份数据库，并在发布说明中标注迁移影响。

## 8. 共享契约

`packages/shared-contract` 存放：

- OpenAPI。
- JSON Schema。
- 事件契约。

前端临时手写类型只能作为占位。后续应通过 OpenAPI Client 或 JSON Schema 生成类型，减少前后端重复定义。

契约变更要求：

- 同步更新前端类型引用。
- 同步更新后端 DTO / 校验。
- 同步更新文档中的字段说明。
- 涉及工作流节点、变量、智能体输出和权限动作时必须补测试。

## 9. 部署演进

第一阶段：

- 单体 API。
- 单前端应用。
- PostgreSQL、Redis、RabbitMQ、MinIO。
- Worker 可占位。

第二阶段：

- 文档生成和外部交付任务进入 Worker。
- MCP 网关能力增强。
- 审计查询和运行记录可分页检索。

第三阶段：

- 独立 MCP 网关服务。
- 独立执行引擎或调度服务。
- SAML / SCIM / MFA、平台级 SSO 和单点登出增强。
- 多租户资源配额、监控告警和成本统计。

不要第一版就拆太多服务。Agentum 的复杂性应该先收束在清晰的领域边界和数据库模型里。
