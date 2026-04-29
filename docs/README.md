# Agentum 项目说明与实现规范

## 文档索引

本目录用于沉淀 Agentum 的产品、架构和工程规范。

- [architecture.md](./architecture.md)：系统架构、模块边界、核心链路。
- [technology-stack.md](./technology-stack.md)：推荐语言、前后端技术栈、React 与 Vue3 对比。
- [workflow-engine.md](./workflow-engine.md)：工作流定义、节点协议、变量系统、执行状态机。
- [permission-model.md](./permission-model.md)：角色、资源权限、审计与敏感操作。
- [frontend-guidelines.md](./frontend-guidelines.md)：前端画布、交互、视觉和组件规范。
- [development-standards.md](./development-standards.md)：代码格式、命名、注释、接口与错误规范。
- [skills-and-mcp.md](./skills-and-mcp.md)：推荐内置 Skills、MCP 服务和智能体模板。
- [project-structure.md](./project-structure.md)：代码目录、工程骨架和本地开发基础设施。

## 1. 项目定位

Agentum 是一个面向企业 SOP 场景的智能体装配式工作流平台。

它的目标不是做一个通用聊天机器人，也不是复刻 Dify / n8n 这类完全自由的画布系统，而是通过“固定节点类型 + 原子能力装配 + 可审计执行”的方式，让企业用户可以低门槛地搭建严谨、可控、可交付的多智能体流程。

核心理念：

- **原子能力独立管理**：Skills、MCP 服务、智能体模板、知识库、模型配置都作为独立资产维护。
- **智能体灵活装配**：每个智能体可以配置系统提示词、Skills、MCP、知识库、模型参数、输出格式和交互模式。
- **工作流强约束组合**：节点类型固定，但节点之间可顺序、并行、嵌套、分支、合并、暂停、回退。
- **执行过程可观测**：每次运行都保留输入、输出、耗时、调用工具、Token、异常、重试和人工操作记录。
- **最终结果可交付**：流程产物可以生成 Word / PDF、发送邮件、创建 OA 流程、推送 IM、写入数据库或回调外部系统。

## 2. 产品目标

### 2.1 适用场景

Agentum 优先服务于有固定流程、需要多人/多系统协作、对结果可靠性有要求的企业场景，例如：

- 需求分析与评审
- 合同、公文、报告生成
- 风险评估与合规审核
- 项目立项材料生成
- 周报、月报、经营分析报告
- 数据查询、整理、分析与交付
- 内部知识问答后生成正式文档

### 2.2 设计原则

- **严谨优先**：关键业务流程必须可追踪、可审核、可回放。
- **灵活但不失控**：灵活性来自节点组合和能力装配，不来自无限制自定义。
- **以交付物为中心**：平台不只输出聊天文本，而是帮助用户完成业务产物。
- **人机协作**：用户输入、追问确认、人工审核都是流程的一等能力。
- **资产复用**：优秀的 Skills、MCP 和智能体模板应可沉淀、复用、版本化。

## 3. 核心概念

### 3.1 原子能力

原子能力是 Agentum 的基础资产，建议独立管理生命周期、权限和版本。

| 类型 | 说明 | 示例 |
| --- | --- | --- |
| Skill | 提示词层面的专业能力增强 | 文档摘要、合同审查、代码审查、风险评估 |
| MCP 服务 | 外部系统、工具或数据源连接能力 | 数据库查询、邮件发送、飞书/钉钉、Google Drive |
| 智能体模板 | 可复用的智能体配置模板 | 需求分析智能体、审核判断智能体、报告组装智能体 |
| 知识库 / RAG | 企业内部知识或业务资料 | 制度库、项目文档库、产品知识库 |
| 模型配置 | 模型供应商、模型名、温度、上下文长度等 | OpenAI、Claude、本地模型 |

### 3.2 智能体

智能体是平台的核心执行单元。一个智能体应包含以下配置：

- 名称、描述、适用场景
- 系统提示词
- 已装配 Skills
- 已装配 MCP 服务
- 可访问知识库
- 模型与参数
- 输入变量定义
- 输出变量定义
- 输出格式要求
- 交互模式：一次性输出、追问模式、内嵌用户输入后继续
- 权限范围
- 版本号与发布状态

### 3.3 工作流

工作流由固定类型节点组成。平台应避免让用户无限自定义节点类型，而是通过稳定、可理解的节点能力覆盖主要业务场景。

推荐节点类型：

| 节点类型 | 作用 | 是否可暂停 |
| --- | --- | --- |
| 触发节点 | 手动、定时、Webhook、外部事件触发流程 | 否 |
| 用户输入节点 | 表单、文件上传、补充信息 | 是 |
| 智能体节点 | 执行 AI 分析、生成、判断、提取等任务 | 可选 |
| 并行节点组 | 同时执行多个子任务 | 取决于子节点 |
| 合并 / 组装节点 | 汇聚多个变量，透传或由组装智能体综合处理 | 可选 |
| 条件分支节点 | 基于变量、规则或外部返回值进行分支 | 否 |
| 人工审核节点 | 人工确认、修改、通过、驳回 | 是 |
| 交付节点 | 生成文件、发邮件、建 OA、写数据库、Webhook 回调 | 否 |

## 4. MVP 实现范围

第一阶段建议先完成一个闭环，而不是一次性追求大而全。

推荐 MVP 流程：

```text
用户输入 -> 智能体分析 -> 用户确认/追问 -> 并行数据获取 -> 合并组装 -> 人工审核 -> 文档/邮件交付
```

第一阶段必须具备：

- 工作流创建、编辑、保存
- 固定节点类型配置
- 智能体配置与绑定
- Skills / MCP / 智能体模板的基础管理
- 变量声明与下游引用
- 智能体一次性输出
- 智能体追问/确认模式
- 用户输入节点与流程暂停恢复
- 并行节点组与合并节点
- 条件分支
- 人工审核
- 执行日志
- 至少一种交付能力，例如生成 Word / PDF 或发送邮件

第一阶段可以暂缓：

- 复杂市场化插件体系
- 多租户计费
- 高级可视化运行分析
- 复杂嵌套子流程
- 非技术用户完全自由拖拽画布

## 5. 推荐技术栈

### 5.1 总体建议

结合项目长期维护、企业级稳定和后期少改动的目标，第一阶段推荐采用 **前端 TypeScript + 后端 Java / Kotlin** 的主路线。

原因：

- 前端画布、动态表单、节点配置天然适合 TypeScript。
- 后端会承载权限、审计、执行状态机、事务和企业系统集成，Java / Kotlin 更适合长期维护。
- 工作流定义、节点协议、变量 schema 通过 OpenAPI / JSON Schema 保持前后端一致。
- 后续如执行压力变大，可将调度层或 Worker 拆成独立服务。

### 5.2 前端

推荐：

- 语言：TypeScript
- 框架：React
- 构建：Vite 或 Next.js
- UI：Tailwind CSS + Radix UI / shadcn/ui
- 图标：lucide-react
- 画布：React Flow
- 表单：React Hook Form + Zod
- 状态管理：Zustand 或 TanStack Query
- 请求：OpenAPI Client / tRPC / TanStack Query

前端重点：

- 画布体验要清晰，不追求炫技。
- 节点卡片要信息密度适中，颜色用于区分节点类型。
- 配置面板要稳定、统一、可扫描。
- 用户要能清楚看到每个节点的输入变量、输出变量和暂停点。

### 5.3 后端

推荐：

- 语言：Java 21 或 Kotlin
- 框架：Spring Boot
- 数据库：PostgreSQL
- ORM：Spring Data JPA / MyBatis / jOOQ，按团队习惯选择
- 缓存与队列：Redis + RabbitMQ / Kafka
- 文件存储：本地存储、S3 兼容存储或企业对象存储
- 鉴权：JWT / Session + RBAC
- API 文档：OpenAPI

后端重点：

- 工作流配置和执行实例必须分离。
- 节点执行应有明确状态机。
- 每个执行步骤都要落库。
- MCP 调用、模型调用、文件生成等外部操作必须可审计。
- 长耗时任务应进入队列，不阻塞接口请求。

### 5.4 AI 与工具调用

建议抽象统一的 AI Provider 接口，避免业务代码直接绑定单一模型厂商。

推荐抽象：

- Chat Completion
- Structured Output
- Tool Calling
- Embedding
- Rerank
- File Parsing
- Document Generation

MCP 调用应经过平台统一网关，方便做权限控制、参数校验、审计、限流和密钥隔离。

## 6. 核心数据模型建议

### 6.1 工作流定义与运行实例

工作流定义是模板，运行实例是某次执行。

推荐拆分：

- `WorkflowDefinition`：工作流配置
- `WorkflowNodeDefinition`：节点配置
- `WorkflowEdgeDefinition`：节点连接关系
- `WorkflowRun`：一次运行实例
- `NodeRun`：某个节点的一次运行记录
- `VariableSnapshot`：运行中的变量快照

### 6.2 原子能力资产

推荐资产模型：

- `Skill`
- `McpService`
- `AgentTemplate`
- `KnowledgeBase`
- `ModelProvider`
- `ToolCredential`

所有资产都应支持：

- 版本号
- 发布状态
- 所属空间
- 创建者
- 更新者
- 权限范围
- 启用/停用
- 审计日志

### 6.3 变量系统

变量是工作流能否严谨串联的关键。

变量要求：

- 每个节点必须声明输出变量。
- 下游节点只能引用已存在变量。
- 变量应有类型，例如 `string`、`number`、`boolean`、`object`、`array`、`file`、`decision`。
- 重要变量建议支持 JSON Schema。
- 每次运行应保存变量快照，便于回放和排错。

变量示例：

```json
{
  "project_info": {
    "type": "object",
    "sourceNodeId": "user_input_1",
    "description": "用户提交的项目基础信息"
  },
  "risk_level": {
    "type": "number",
    "sourceNodeId": "agent_risk_review",
    "description": "风险等级，1-5"
  },
  "final_report": {
    "type": "file",
    "sourceNodeId": "delivery_doc",
    "description": "最终生成的报告文件"
  }
}
```

## 7. 权限设计

### 7.1 权限模型

推荐采用 **RBAC + 资源级权限**。

RBAC 用于定义角色，资源级权限用于控制具体资产是否可读、可编辑、可执行、可发布。

### 7.2 推荐角色

| 角色 | 说明 |
| --- | --- |
| 系统管理员 | 管理全局配置、用户、租户、模型、系统级 MCP |
| 空间管理员 | 管理某个业务空间内的成员、资产和权限 |
| 流程设计者 | 创建和编辑工作流、配置节点、引用资产 |
| 智能体管理员 | 创建、维护、发布智能体模板 |
| 能力管理员 | 管理 Skills、MCP、知识库和工具凭证 |
| 审核人 | 处理人工审核节点，确认、修改、驳回流程 |
| 执行人 | 触发流程、填写输入、查看自己的运行结果 |
| 观察者 | 只读查看流程、资产或执行记录 |

### 7.3 资源权限

每类资源建议至少支持以下权限：

- `read`：查看
- `create`：创建
- `update`：编辑
- `delete`：删除
- `execute`：执行
- `publish`：发布
- `approve`：审核
- `manage_permission`：管理权限

资源范围：

- Workflow
- AgentTemplate
- Skill
- McpService
- KnowledgeBase
- ToolCredential
- WorkflowRun
- AuditLog
- DeliveryTarget

### 7.4 敏感权限要求

以下操作必须记录审计日志：

- 修改系统提示词
- 修改 MCP 服务配置
- 绑定或解绑工具凭证
- 发布智能体模板
- 发布工作流
- 执行生产环境工作流
- 人工审核通过或驳回
- 向外部系统推送交付物
- 下载敏感文件

以下操作必须二次确认：

- 删除工作流
- 删除原子能力资产
- 启用生产环境交付节点
- 修改生产环境 MCP 凭证
- 批量执行流程

## 8. 执行状态机

工作流运行建议使用统一状态机。

### 8.1 WorkflowRun 状态

- `pending`：等待开始
- `running`：执行中
- `paused`：等待用户输入、人工审核或外部回调
- `completed`：成功完成
- `failed`：执行失败
- `canceled`：用户取消
- `timeout`：执行超时

### 8.2 NodeRun 状态

- `pending`：等待执行
- `running`：执行中
- `paused`：节点暂停
- `skipped`：被条件跳过
- `completed`：成功完成
- `failed`：执行失败
- `retrying`：等待重试
- `canceled`：取消

### 8.3 暂停与恢复

用户输入、智能体追问、人工审核、外部回调本质上都是暂停点。

建议底层统一为：

```text
node_run paused -> waiting_event -> resume payload -> continue
```

这样可以避免每类暂停节点各自实现一套恢复逻辑。

## 9. 前端设计要求

### 9.1 整体风格

前端需要体现“企业级、清晰、专业、美观”。

要求：

- 界面简洁，不使用花哨但无意义的装饰。
- 信息层级清楚，用户能快速判断当前在哪、配置了什么、下一步是什么。
- 色彩用于表达节点类型和状态，不要大面积使用单一渐变色。
- 卡片、按钮、表单、弹窗、侧边栏风格统一。
- 页面不做营销式 Landing，优先展示真实可用的工作台。

### 9.2 画布设计

画布是产品核心，应优先保证可读性。

节点视觉建议：

- 触发节点：中性色
- 用户输入节点：橙色
- 智能体节点：蓝色
- 并行节点组：绿色
- 合并 / 组装节点：紫色
- 条件分支节点：黄色
- 人工审核节点：红色
- 交付节点：橘红色

节点卡片必须展示：

- 节点名称
- 节点类型
- 关键配置摘要
- 输入变量
- 输出变量
- 是否暂停
- 执行状态

### 9.3 配置面板

节点配置建议使用右侧抽屉。

通用结构：

```text
基础信息
输入变量
核心配置
输出变量
权限与审计
高级设置
```

要求：

- 表单字段命名统一。
- 必填项清晰标识。
- 错误提示具体，不只提示“配置错误”。
- 变量选择使用可点击插入，不要求用户手写变量名。
- 高级配置默认折叠。

### 9.4 运行态页面

运行态页面必须让用户看清流程执行进度。

应展示：

- 当前运行状态
- 当前执行节点
- 节点耗时
- 节点输入/输出
- 暂停原因
- 等待谁处理
- 错误原因与重试入口
- 最终交付物

## 10. 代码风格与格式统一

### 10.1 语言规范

推荐全项目使用：

- TypeScript
- Markdown
- JSON / YAML
- SQL

原则：

- 业务代码尽量使用 TypeScript。
- 禁止在核心业务里使用隐式 `any`。
- 对外 API、工作流节点、变量、智能体输出必须有明确类型。
- 复杂 JSON 配置必须有 Zod 或 JSON Schema 校验。

### 10.2 命名规范

文件命名：

- 前端组件：`PascalCase.tsx`
- 普通工具函数：`camelCase.ts`
- 后端模块目录：`kebab-case`
- 数据库表名：`snake_case`
- API 路径：`kebab-case`

变量命名：

- TypeScript 变量与函数使用 `camelCase`
- 类型、接口、组件使用 `PascalCase`
- 常量使用 `UPPER_SNAKE_CASE`
- 数据库字段使用 `snake_case`

示例：

```ts
type WorkflowNodeDefinition = {
  nodeId: string;
  nodeType: "agent" | "user_input" | "condition" | "delivery";
  outputVariables: VariableDefinition[];
};
```

### 10.3 格式工具

建议强制使用：

- ESLint
- Prettier
- TypeScript strict mode
- Markdown lint
- Commit lint

推荐规则：

- 缩进使用 2 个空格。
- 每行尽量不超过 100-120 字符。
- 字符串默认使用双引号或项目统一规则，避免混用。
- import 排序自动化。
- 保存时自动格式化。

## 11. 注释规范

项目注释统一使用中文，便于业务团队和后续维护者理解。

### 11.1 必须写中文注释的场景

- 工作流状态机
- 权限判断
- 变量解析与注入
- MCP 调用网关
- 模型输出结构化解析
- 重试、超时、补偿逻辑
- 审计日志
- 涉及企业业务规则的判断

### 11.2 不建议写注释的场景

- 代码已经能直接表达意图的简单赋值
- 只重复函数名含义的注释
- 无业务含义的样式类组合

### 11.3 示例

推荐：

```ts
// 只有已发布的工作流才能在生产环境执行，草稿流程只允许设计者调试。
if (workflow.status !== "published" && env === "production") {
  throw new WorkflowPermissionError("当前工作流未发布，不能在生产环境执行");
}
```

不推荐：

```ts
// 判断状态
if (workflow.status !== "published") {
  // 抛出错误
  throw new Error("error");
}
```

## 12. 接口与错误规范

### 12.1 API 返回格式

建议统一格式：

```json
{
  "success": true,
  "data": {},
  "error": null,
  "requestId": "req_xxx"
}
```

错误格式：

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "WORKFLOW_NODE_CONFIG_INVALID",
    "message": "智能体节点缺少输出变量配置",
    "details": {}
  },
  "requestId": "req_xxx"
}
```

### 12.2 错误码原则

- 错误码使用大写下划线。
- message 使用中文。
- 内部错误不能直接暴露密钥、SQL、堆栈或供应商原始响应。
- 所有外部系统调用错误都要保留可审计的内部日志。

## 13. 安全要求

基础要求：

- 密钥只允许存放在服务端，不进入前端。
- MCP 凭证必须加密存储。
- 用户只能调用自己有权限的 MCP 服务。
- 生产环境执行需要校验工作流发布状态。
- 文件上传必须限制类型和大小。
- 文件解析必须隔离执行。
- 外部 Webhook 必须支持签名校验。
- 重要操作必须记录审计日志。

AI 特有风险：

- 系统提示词不得暴露给无权限用户。
- 工具调用前必须做权限检查。
- 重要交付节点建议支持人工审核。
- 模型输出必须经过 schema 校验后才能进入条件分支或外部系统。
- 高风险 MCP 调用需要二次确认或审批。

## 14. 推荐目录结构

第一阶段可以采用单仓库结构。

```text
Agentum/
  docs/
    README.md
  apps/
    web/
    api/
  packages/
    shared-contract/
    ui/
  workers/
    document-worker/
    ai-worker/
  deploy/
  scripts/
  .env.example
  .gitignore
```

说明：

- `apps/web`：React 前端应用。
- `apps/api`：Java / Kotlin 后端 API 服务，内部包含工作流、智能体、权限、审计、交付等领域模块。
- `packages/shared-contract`：OpenAPI、JSON Schema、节点协议、变量协议等共享契约。
- `packages/ui`：前端通用 UI 组件。
- `workers/document-worker`：文档解析、Word / PDF 生成等后台任务。
- `workers/ai-worker`：可选 AI Worker，用于复杂模型调用、批处理或 Python 生态任务。
- `deploy`：部署、Docker、环境配置。

构建与命令入口：

- 前端使用 `pnpm workspace`。
- 后端与 Java Worker 使用 Gradle Kotlin DSL，即 `build.gradle.kts` 和 `settings.gradle.kts`。
- 本地基础设施使用 `docker-compose.dev.yml`。
- 常用命令通过 `Makefile` 做快捷封装。

## 15. 版本与发布规范

### 15.1 资产版本

以下资产必须支持版本：

- Workflow
- AgentTemplate
- Skill
- McpService
- KnowledgeBase

建议规则：

- 草稿版本可编辑。
- 发布版本不可直接修改，只能创建新版本。
- 工作流运行时记录所使用的资产版本。
- 支持回滚到历史版本。

### 15.2 发布流程

推荐流程：

```text
编辑草稿 -> 校验配置 -> 测试运行 -> 提交发布 -> 审核通过 -> 正式发布
```

校验内容：

- 是否存在孤立节点
- 是否存在无输出变量的关键节点
- 下游变量引用是否有效
- 条件分支是否覆盖默认路径
- 交付节点是否配置目标
- MCP 权限是否满足
- 人工审核人是否存在

## 16. 后续路线

建议按阶段推进：

### 阶段一：核心闭环

- 工作流编辑
- 智能体节点
- 用户输入
- 变量系统
- 并行与合并
- 人工审核
- 基础交付
- 执行日志

### 阶段二：资产治理

- Skills 库
- MCP 服务库
- 智能体模板库
- 版本管理
- 权限管理
- 审计日志

### 阶段三：企业增强

- 多租户
- SSO
- 高级审批
- 生产/测试环境隔离
- 更细粒度的 MCP 凭证管理
- 流程运行分析

### 阶段四：生态扩展

- 插件市场
- 模板市场
- 第三方系统连接器
- 私有化部署方案
- 多模型策略与成本优化

## 17. 当前结论

Agentum 的关键竞争力不在于“能不能拖画布”，而在于：

- 能把企业 AI 能力资产化。
- 能把智能体流程标准化。
- 能让人类在关键节点介入。
- 能把 AI 结果交付到真实业务系统。
- 能在执行过程中留下足够清晰的证据链。

因此，第一版实现应围绕“可装配、可暂停、可审计、可交付”四个关键词展开。
