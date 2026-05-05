# 开发规范

本文档是 Agentum 的长期开发规范。当前阶段、已完成内容和后续任务不写在本文档正文中，统一维护在 [progress/README.md](./progress/README.md)。

## 1. 开发前阅读

每次开发前必须先阅读：

- `README.md`
- `AGENTS.md`
- `docs/development-standards.md`
- `docs/system-overview.md`
- `docs/architecture.md`
- `docs/progress/README.md`

涉及前端体验时还要阅读 `.codex/skills/ui-ux-pro-max/SKILL.md`。

## 2. 总体原则

- 先理解现有代码和文档，再修改。
- 修改范围尽量小，不顺手重构无关模块。
- 注释、错误 message、产品文案和项目文档默认使用中文。
- 优先复用已有类型、组件、工具函数、接口格式和测试风格。
- 前端入口隐藏只作为体验优化，后端必须重新校验权限。
- 新增复杂逻辑必须补中文注释，说明业务原因、临时模拟数据来源和后续替换方向。
- 核心逻辑必须有测试，尤其是权限、状态机、变量解析、MCP 调用、模型输出解析、审计、重试和交付。

## 3. 文档维护

长期主文档只维护三份：

| 文档 | 作用 |
| --- | --- |
| `docs/development-standards.md` | 开发规范、接口规范、测试要求和交付要求 |
| `docs/system-overview.md` | 产品定位、角色视角、工作流、能力资产、运行态和权限说明 |
| `docs/architecture.md` | 架构、模块边界、技术栈、数据库和部署演进 |

当前执行状态、阶段计划、后续任务和临时决策维护在：

| 文档 | 作用 |
| --- | --- |
| `docs/progress/README.md` | 当前进度、下一步、后续排期和验证记录 |

新增长期设计内容时，应优先合并到三份主文档，不再新增零散专题文档。只有明确属于“当前施工记录、版本进度、阶段任务”的内容，才放到 `docs/progress/`。

## 4. 推荐目录

```text
Agentum/
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
  docs/
    development-standards.md
    system-overview.md
    architecture.md
    progress/
      README.md
```

前端页面按产品区域放在 `apps/web/src/surfaces/`，可复用能力放在 `apps/web/src/features/`。

```text
apps/web/src/
  surfaces/
    auth/
    workbench/
    designer/
    assets/
    audit/
    admin/
  features/
    auth/
    workflow/
    assets/
    runs/
    permission/
    audit/
  components/
  services/
  stores/
  types/
```

后端保持 Spring Boot 标准结构，先做单体模块边界，后续再按压力拆服务。

```text
apps/api/src/main/java/com/agentum/
  auth/
  tenant/
  organization/
  permission/
  workflow/
  agent/
  asset/
  mcp/
  modelprovider/
  delivery/
  audit/
  system/
  shared/
```

当前第一阶段不再把知识库作为核心模块推进。如后续重新引入 RAG，应在 `asset` 或独立 `knowledge` 模块下按版本、权限和审计重新设计。

## 5. 命名规范

前端：

- 组件：`PascalCase`
- hooks：`useXxx`
- 工具函数：`camelCase`
- 类型：`PascalCase`
- 常量：`UPPER_SNAKE_CASE`

后端：

- 类名：`PascalCase`
- 方法名：`camelCase`
- 包名：小写
- 数据库表名：`snake_case`
- 数据库字段名：`snake_case`

API 路径使用 `kebab-case`，数据库主键优先使用 UUID。

## 6. 注释规范

必须写中文注释的场景：

- 工作流状态机、暂停恢复、回退、取消、重试和补偿。
- 权限判断、租户上下文、角色切换、敏感动作审批。
- 变量解析、变量快照、输出 Schema 校验。
- MCP 调用、凭证注入、参数脱敏、频率限制。
- 模型输出解析、Prompt 组装、Skills 注入、追问确认。
- 审计日志、交付记录、外部系统回调。
- 前端复杂交互状态，例如画布选中、右侧配置面板、运行态筛选、待办处理。
- 暂时使用静态数据或模拟数据的地方，必须说明后续替换为哪个 API 或契约。

注释解释“为什么这样做”和“承担什么业务约束”，不要只复述代码表面行为。

示例：

```ts
// 业务工作台只展示当前用户能处理的暂停点，真实权限仍由后端根据租户、角色和资源策略重新校验。
const visibleTodos = todos.filter((todo) => todo.assigneeId === currentUser.id);
```

## 7. 接口规范

API 返回结构统一：

```json
{
  "success": true,
  "data": {},
  "error": null,
  "requestId": "req_xxx"
}
```

错误结构统一：

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

要求：

- `message` 使用中文。
- 内部错误不能暴露 SQL、堆栈、密钥、供应商原始敏感响应。
- `requestId` 必须能串联 API 日志、节点运行记录、MCP 调用和审计日志。

## 8. 错误码规范

错误码使用大写下划线。

推荐前缀：

- `AUTH_`
- `TENANT_`
- `ORG_`
- `PERMISSION_`
- `WORKFLOW_`
- `NODE_`
- `VARIABLE_`
- `AGENT_`
- `SKILL_`
- `MCP_`
- `MODEL_`
- `DELIVERY_`
- `AUDIT_`
- `SYSTEM_`

## 9. 测试要求

必须测试：

- 认证、会话恢复、角色切换和租户上下文。
- 用户、部门、角色和权限策略的关键判断。
- 工作流发布校验。
- 节点状态流转、暂停恢复、回退、取消、重试。
- 条件分支和并行合并。
- 变量声明、引用、类型校验和快照。
- 智能体输出 Schema 校验。
- MCP 权限控制、参数脱敏和高风险审批。
- 交付节点失败重试。
- 审计日志写入和查询脱敏。

前端静态演示阶段至少运行类型检查、lint 或 build。后端引入数据库迁移后至少运行 Gradle 测试或启动校验。

## 10. 格式化要求

前端：

- ESLint
- Prettier 或项目统一格式
- TypeScript strict mode

后端：

- Java 21
- Spring Boot 3
- Gradle Kotlin DSL
- 后续可引入 Spotless / Checkstyle

数据库：

- PostgreSQL
- Flyway 迁移脚本
- 迁移文件随代码提交，不允许手工改库后不提交版本脚本。

## 11. 前端体验要求

- 保持企业工作台风格，不做营销页式布局。
- 普通业务人员默认不看画布，只看待办、可用流程、运行进度和交付物。
- 流程设计者进入完整画布和节点配置。
- 能力管理员管理智能体模板、Skills、MCP、提示词模板和交付能力。
- 系统管理员管理租户、模型、全局能力开关、系统配置和敏感凭证策略。
- 权限管理要支持用户、部门、角色、资源权限和敏感动作控制。
- 运行态必须清楚展示当前步骤、暂停原因、等待对象、可执行动作和最终交付物。
- 图标优先使用 `lucide-react`。
- UI 元素不要互相遮挡，移动端至少可浏览核心信息。

## 12. 每次交付要求

- 说明本次属于哪个阶段和任务。
- 同步更新相关文档、契约和测试。
- 新增或修改接口时更新 OpenAPI / JSON Schema。
- 新增数据库结构时更新 Flyway 迁移。
- 完成后运行影响范围内的验证。
- 如果无法验证，最终说明必须写明原因和剩余风险。
