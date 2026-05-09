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
- 前端表单、输入框、选择器、弹窗、抽屉、提示消息、表格、分页、日期选择、上传和权限配置类控件优先使用 Ant Design；只有业务工作台布局、品牌视觉、画布和节点等 Agentum 自有体验层才使用本地 CSS / Tailwind 补充样式。
- 菜单权限必须区分左侧大模块和模块内页签：左侧菜单由模块权限控制，模块内页签 / 顶层菜单由当前模块的小权限控制；租户管理员按角色、部门、人员分配租户内权限，后端必须复核。
- 当前静态卡片只用于概览和风险摘要，不作为生产主导航；真实页面优先使用列表、表单、详情、授权矩阵和审计记录。
- 新增复杂逻辑必须补中文注释，说明业务原因、临时模拟数据来源和后续替换方向。
- 涉及权限、租户上下文、认证、成员关系、状态流转、外部调用和失败分支的代码必须有适度中文注释，说明业务约束、安全边界和后续替换方向。
- 后端关键路径必须补结构化日志，前端关键失败分支必须补用户可见错误和必要的脱敏诊断输出。
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
  capabilities/
    skills/
    mcp-servers/
    prompt-templates/
    delivery/
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

`capabilities/` 存放产品运行时可版本化的能力源码和自研连接器，不等同于数据库里的资产记录：

- `capabilities/skills/`：产品运行时 Skill 的说明、提示词片段、输入输出约束、测试样例和发布材料。
- `capabilities/mcp-servers/`：自研 MCP Server 的源码、manifest、启动说明、测试和部署配置。
- `capabilities/prompt-templates/`：可复用提示词模板源码，发布后再登记为提示词模板资产。
- `capabilities/delivery/`：交付能力适配器、文档模板、脚本和本地验证材料。

`.codex/skills/` 只用于本仓库开发时辅助 Codex 工作，不作为 Agentum 产品运行时 Skill 资产目录。

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
- 后端认证、租户上下文、角色入口、系统管理员跨租户访问、用户成员关系写入和权限拒绝分支。
- 前端 API client、会话恢复、权限动作提交、弹窗表单和真实后端数据替换静态展示的位置。

注释解释“为什么这样做”和“承担什么业务约束”，不要只复述代码表面行为。

注释密度要求：

- 简单 getter、字段声明、直观 JSX 布局不需要逐行注释。
- 每个有业务判断的 service / store / hook / controller 至少在关键分支前保留中文注释。
- 每个临时实现、mock、开发 seed、后续待替换策略必须写明“当前为什么这样做”和“后续替换方向”。
- 不允许用注释掩盖坏命名；如果代码需要大量解释才能看懂，应优先拆函数或改名。

示例：

```ts
// 业务工作台只展示当前用户能处理的暂停点，真实权限仍由后端根据租户、角色和资源策略重新校验。
const visibleTodos = todos.filter((todo) => todo.assigneeId === currentUser.id);
```

## 7. 日志规范

后端日志要求：

- 使用 SLF4J，不使用 `System.out.println`。
- 认证成功、认证失败、令牌失效、权限拒绝、成员 / 部门 / 角色写入、外部系统调用、交付动作、审计写入失败必须记录日志。
- 日志默认中文，保留结构化上下文，例如 `requestId`、`tenantId`、`userId`、`resourceType`、`resourceId`、`action`、`errorCode`。
- `info` 用于成功的关键业务动作，例如登录成功、创建成员、创建部门。
- `warn` 用于可预期失败或安全相关拒绝，例如登录失败、权限不足、Token 过期、租户不可用。
- `error` 用于非预期异常、外部依赖失败、数据不一致和审计写入失败。
- 禁止输出密码、Token、密钥、凭证明文、完整请求头、完整 Cookie、供应商敏感原始响应和文件敏感内容。

前端日志要求：

- 用户操作失败必须有用户可见错误提示，不能只写控制台。
- 可使用 `console.warn` / `console.error` 记录脱敏诊断信息，例如接口路径、错误码、requestId。
- 禁止输出密码、Token、密钥、完整用户输入、完整后端敏感响应。
- 生产接入统一前端监控后，应将关键失败事件上报到监控服务，控制台日志只保留开发辅助信息。

示例：

```java
log.warn("成员创建被拒绝：租户角色不可用 tenantId={} roleId={} requestId={}", tenantId, roleId, requestId);
```

```ts
console.warn("租户列表加载失败", { code: error.code, requestId: error.requestId });
```

## 8. 接口规范

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

## 9. 错误码规范

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

## 10. 测试要求

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

## 11. 格式化要求

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

## 12. 前端体验要求

- 保持企业工作台风格，不做营销页式布局。
- 普通业务人员默认不看画布，只看待办、可用流程、运行进度和交付物。
- 流程设计者进入完整画布和节点配置。
- 能力管理员管理智能体模板、Skills、MCP、提示词模板和交付能力。
- 系统管理员管理租户、模型、全局能力开关、系统配置和敏感凭证策略。
- 租户管理承担当前租户内管理职责，支持用户、部门、角色、空间、资源权限、需求配置和敏感动作控制；权限边界参照 AuraOA 的系统管理员 / 租户内管理员分层设计。
- 当前静态卡片页面只是信息层级抽象，生产页面要逐步改造成概览、顶层菜单 / 页签、列表、详情、表单、授权矩阵和审计记录，并保留跨页面的数据勾稽关系。
- 运行态必须清楚展示当前步骤、暂停原因、等待对象、可执行动作和最终交付物。
- 审核、运行监控和运行审计必须分层：业务待办处理暂停恢复，运行监控处理取消 / 重试 / 补偿，运行审计只读展示证据链。
- 图标优先使用 `lucide-react`。
- UI 元素不要互相遮挡，移动端至少可浏览核心信息。

## 13. 每次交付要求

- 说明本次属于哪个阶段和任务。
- 同步更新相关文档、契约和测试。
- 新增或修改接口时更新 OpenAPI / JSON Schema。
- 新增数据库结构时更新 Flyway 迁移。
- 新增或修改复杂逻辑时检查中文注释是否解释业务原因，检查关键成功 / 失败路径是否有脱敏日志。
- 完成后运行影响范围内的验证。
- 如果无法验证，最终说明必须写明原因和剩余风险。
