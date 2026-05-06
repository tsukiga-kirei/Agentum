# 架构文档

## 1. 架构目标

Agentum 的架构目标是支撑长期演进的企业级智能体工作流，而不是只做一个可演示画布。

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
| 前端 | React、TypeScript、Vite、React Flow、Tailwind CSS、lucide-react、Zustand |
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
  |-- 权限策略与资源授权
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

Agentum 需要同时管理“能力资产记录”和“能力可执行实现”。资产记录解决谁能看、谁能用、哪个版本已发布；可执行实现解决 Skill、MCP Server、提示词模板和交付适配器到底如何开发、测试、部署。

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
  prompt-templates/
    README.md
    <template-key>.prompt.md
  delivery/
    README.md
    <delivery-key>/
      templates/
      src/
      tests/
```

边界规则：

- `apps/api` 是控制面和治理面，负责能力登记、租户授权、权限校验、凭证注入、调用审计和版本发布。
- `capabilities/skills/` 保存产品运行时 Skill 源文件，发布后在数据库 `skills` 或资产表里形成版本记录。
- `capabilities/mcp-servers/` 保存自研 MCP Server 源码。每个 Server 应能独立启动、测试和部署，API 的 MCP 网关只通过注册信息调用它，不把具体业务连接逻辑写进网关。
- `capabilities/prompt-templates/` 保存提示词模板源码，发布后再作为提示词模板资产被智能体模板或节点引用。
- `capabilities/delivery/` 保存交付适配器、文档模板和本地验证材料，发布后再登记为交付能力资产。
- `packages/shared-contract` 保存 MCP、Skill、智能体、工作流和事件的共享 Schema，不保存具体能力实现。
- 数据库中的 `skills`、`mcp_services`、`prompt_templates`、`delivery_capabilities` 更像资产注册表，不应直接承担源码仓库职责。
- `.codex/skills/` 是本仓库开发辅助技能目录，不进入 Agentum 产品运行时能力发布链路。

自研 MCP Server 的推荐接入链路：

```text
capabilities/mcp-servers/<server-key>
  -> 本地测试和 manifest 校验
  -> 系统管理员登记为全局 MCP
  -> 授权给租户
  -> 租户 / 空间管理员授权给角色、智能体或工作流
  -> 运行时由 MCP 网关统一鉴权、注入凭证、脱敏和审计
```

## 4. 前端边界

前端负责：

- 登录页、工作台壳层和角色入口展示。
- 业务工作台：待办、可用流程、业务运行详情、运行态摘要、交付物。
- 流程设计：工作流列表、画布、节点配置、变量面板。
- 能力资产：智能体模板、Skills、MCP、提示词模板、交付能力。
- 运行监控：运行状态、失败重试、取消、补偿和管理员介入入口。
- 运行审计：只读执行链路、变量快照、工具调用、审核记录、交付记录和证据链。
- 权限管理：用户、部门、角色、资源授权、敏感动作和审计可见性配置入口。
- 系统管理：租户、模型、全局能力、交付通道和凭证策略配置入口。

前端不负责：

- 认证最终判断。
- 租户、角色、资源权限最终判断。
- MCP 凭证明文读取。
- 模型调用。
- 工作流真实执行。
- 高风险交付审批最终判断。
- 审计页中的业务恢复动作。

前端页面是视图组合层，不是数据模型边界。页面可以为了角色任务聚合多个后端对象，也可以随着产品理解加深继续拆分、合并或改名。

实现约束：

- 页面不能反向要求后端把聚合视图落成同名大表。
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
spaceId
departmentIds
roleIds
requestId
isSystemAdmin
```

可参考 AuraOA 的分层思路：公开登录接口、已登录接口、租户管理员接口、系统管理员接口分组；JWT 解析后注入租户上下文，再做角色校验。

### 5.2 用户、部门、角色

职责：

- 租户成员管理。
- 部门树管理。
- 角色管理。
- 用户角色分配。
- 账号状态管理。
- 邀请、禁用、锁定和密码策略占位。

第一阶段至少需要能描述“谁属于哪个租户 / 部门 / 空间，以什么角色操作哪些资源”。

### 5.3 权限模块

权限模块采用 RBAC + 资源级权限 + 敏感动作控制。

判断顺序：

1. 用户是否登录。
2. 是否有租户或系统管理上下文。
3. 是否属于目标空间或资源范围。
4. 角色是否具备动作能力。
5. 资源授权是否允许访问具体对象。
6. 是否涉及敏感动作。
7. 是否需要审批或二次确认。
8. 写入审计日志。

### 5.4 工作流定义模块

职责：

- 保存工作流草稿。
- 保存固定节点类型配置。
- 管理节点和边。
- 校验变量引用。
- 发布不可变版本。

定义态对象：

- `WorkflowDefinition`
- `WorkflowVersion`
- `WorkflowNodeDefinition`
- `WorkflowEdgeDefinition`
- `WorkflowVariableDefinition`

画布坐标、折叠状态和 UI 选中态只能放在 `layout` 或 `ui_schema`，不能污染执行器协议。

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

状态建议：

```text
pending -> running -> paused -> resumed -> running -> completed
                         |                    |
                         v                    v
                      canceled              failed
```

业务恢复、管理员介入和审计查看应分层处理：

- 业务恢复：用户输入、追问确认、人工审核、高风险审批和交付确认，来自待办详情或业务运行详情。
- 管理员介入：取消、重试、补偿和故障处理，来自运行监控。
- 审计查看：执行链路、节点输入输出快照、工具调用、审核和交付记录，只读展示，不修改运行状态。

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

资产只做“能力是什么”的管理。租户级可用性、角色授权、凭证策略和敏感动作控制由系统管理与权限模块负责。

能力资产页应提供新增、发布、停用、版本管理和引用关系查询。流程节点如何选择和引用资产属于流程设计的节点配置，不应把“流程导入建议”作为能力资产页主内容。

资产管理的界面抽象不应产生额外资源层级。每类资产独立维护创建入口和生命周期；如果页面需要右侧辅助区，优先展示引用概览、缺失授权、高风险待审批、草稿待发布和版本漂移等治理信息。

### 5.8 MCP 网关

职责：

- 注册 MCP 服务。
- 同步工具列表。
- 校验参数 Schema。
- 校验用户、租户、角色、智能体和工作流权限。
- 注入服务端凭证。
- 限流。
- 脱敏结果。
- 写入审计。

模型不能看到所有工具，只能看到当前节点和当前用户被授权的工具。

### 5.9 模型供应商模块

职责：

- 注册模型供应商。
- 管理模型名称、上下文长度、默认参数和可用租户。
- 测试模型连接。
- 统计调用成本和 Token 用量。
- 为租户配置默认模型或允许模型集合。

模型密钥必须服务端加密存储，前端只展示脱敏状态。

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

高风险交付能力必须走权限校验、审批或二次确认。

### 5.11 系统管理模块

系统管理负责平台级配置：

- 租户管理。
- 全局模型供应商。
- 全局 MCP。
- 全局 Skills 和提示词模板。
- 全局交付能力。
- 租户能力授权。
- 系统参数。
- 数据保留策略。
- 凭证安全策略。

系统管理员将能力授权给租户后，租户管理员再在租户内分配给角色、部门、智能体或工作流。

凭证策略、模型密钥和全局 MCP 凭证由系统管理维护；权限模块只判断谁可以申请、审批、调用或查看脱敏状态。

## 6. 数据模型建议

第一阶段优先建立以下表族：

| 表族 | 说明 |
| --- | --- |
| 租户与人员 | `tenants`、`users`、`departments`、`roles`、`user_memberships` |
| 权限 | `permission_policies`、`resource_grants`、`sensitive_action_policies` |
| 系统能力 | `model_providers`、`system_capabilities`、`tenant_capability_grants` |
| 能力资产 | `agent_templates`、`skills`、`mcp_services`、`prompt_templates`、`delivery_capabilities` |
| 工作流定义 | `workflow_definitions`、`workflow_versions`、`workflow_nodes`、`workflow_edges` |
| 工作流运行 | `workflow_runs`、`node_runs`、`variable_snapshots`、`waiting_events` |
| 审计与交付 | `audit_logs`、`delivery_records`、`mcp_call_logs` |

数据库字段必须包含 `created_at`、`updated_at`，重要业务表建议包含 `tenant_id`、`space_id`、`created_by`、`updated_by`。

资源授权要覆盖工作流定义、智能体模板、能力资产、运行记录、变量快照、交付物和审计日志。一个智能体或交付物完成后，只允许被授权用户、部门或角色查看；敏感文件下载和审计日志查看必须作为独立动作校验。

## 7. 数据库版本管理

数据库使用 PostgreSQL，迁移使用 Flyway。

规则：

- 迁移文件放在 `apps/api/src/main/resources/db/migration/`。
- 文件命名使用 `VyyyyMMddHHmm__description.sql` 或项目统一递增版本。
- 每次数据库结构变更必须随代码提交迁移脚本。
- 不允许只修改本地数据库而不提交迁移。
- 表和关键字段必须写中文注释。
- 向后兼容优先，删除字段必须先确认引用关系和迁移策略。

本地启动 API 时，Flyway 应自动执行迁移。生产环境部署前要先备份数据库，并在发布说明中标注迁移影响。

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
- SSO / MFA。
- 多租户资源配额、监控告警和成本统计。

不要第一版就拆太多服务。Agentum 的复杂性应该先收束在清晰的领域边界和数据库模型里。
