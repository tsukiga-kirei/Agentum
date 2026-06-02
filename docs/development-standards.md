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


## 2. 总体原则

- 先理解现有代码和文档，再修改。
- 修改范围尽量小，不顺手重构无关模块。
- 注释、错误 message、产品文案和项目文档默认使用中文。
- 优先复用已有类型、组件、工具函数、接口格式和测试风格。
- 前端入口隐藏只作为体验优化，后端必须重新校验权限。
- 前端整体 UI 设计必须优先考虑美观度与统一性。视觉表现上可参考 AuraOA 的现代设计语言（如适当的高质感卡片、间距、排版与微动效），弱化单纯对某个组件库（如 Ant Design）的教条依赖，通过本地 CSS / Tailwind 补充以达到最佳体验。
- 菜单权限必须区分两重：第一重是 `business`、`tenant_admin`、`system_admin` 三大登录入口；第二重是租户管理员按用户、部门、租户自定义角色分配业务侧大模块、模块内页签、具体动作和资源范围，后端必须复核。
- 管理类页面的视觉骨架按“系统管理”统一：页头（标题+说明） -> 模块切换（Segmented / 页签） -> 内容卡片。概览页面不要单调地只放几个数据，应利用精美的卡片展示列表数据（如：前几个租户、前几个能力），保持界面丰满并注重信息架构的传达。
- Ant Design 只负责复杂交互控件，不直接决定 Agentum 的视觉语言。所有 Ant Design 组件必须接入本项目 CSS 变量与本地样式类，保持登录页、系统管理、租户管理、流程设计等页面的深浅色一致性。
- 使用 Ant Design 的 `Select`、`Drawer`、`Modal`、`Dropdown`、`Popover`、`Tooltip`、`Message` 等会渲染到 `body` 的弹层或浮层时，必须显式配置 `className` / `rootClassName` / `popup.classNames` 或统一 `ConfigProvider` token。不要假设它们能继承页面内部的 `.dark` 或局部 CSS 变量；深色模式下必须覆盖容器背景、标题、正文、边框、关闭按钮、空态文字、选中态和 hover 态。
- 管理台表单中的 Ant Design `Select` 必须使用统一的管理页样式类（如 `agent-admin-select` 和 `agent-select-dropdown`），并按业务含义补 lucide 前缀图标。选择框高度、圆角、边框、占位文字、清除按钮和下拉箭头必须与同一弹窗里的输入框一致。
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
- `capabilities/delivery/`：自定义交付适配器的 Manifest、源码、脚本和本地验证材料；系统内置交付能力（如邮箱）由后端原生实现，不放在本目录。

提示词模板功能仍作为能力资产、前端配置和流程节点引用能力保留；当前不再维护独立的提示词模板源码目录。

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
- 前端复杂交互状态，例如阶段积木选中、右侧配置面板、运行态筛选、待办处理。
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

分页接口约定（管理台列表默认必须支持）：

- 请求参数统一使用：`page`（从 1 开始）、`size`、`sort`（如 `createdAt,desc`）。
- 管理台列表接口默认提供分页，不允许一次性返回全量数据。
- 返回结构建议在 `data` 内包含：`items`、`page`、`size`、`total`、`totalPages`。
- 筛选条件（如 `keyword`、`status`、`tenantId`）与分页参数并列，避免放进不透明 JSON 字段。
- OpenAPI 必须同步声明分页参数与分页响应模型，前后端不得各自约定。

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
- 真实结构迁移放入 `apps/api/src/main/resources/db/migration/schema/`，本地演示和临时开发数据放入 `apps/api/src/main/resources/db/migration/devdata/`。
- `schema` 与 `devdata` 的 Flyway 版本号必须全局唯一；开发期允许删表删字段，但必须说明业务原因、引用影响和后续替换方向。

## 12. 前端体验要求

- 保持企业工作台风格，不做营销页式布局。
- 普通业务人员默认不看设计态编排，只看待办、可用流程、运行进度和交付物。
- 流程设计者进入阶段积木编排和节点配置，不以自由画布作为主要交互。
- 能力管理员管理智能体模板、Skills、MCP、提示词模板和交付能力。
- 系统管理员管理租户、模型、全局能力开关、系统配置和敏感凭证策略。
- 租户管理承担当前租户内管理职责，支持用户、部门、角色、空间、资源范围、可用能力池分配、需求配置和运行安全策略；能力分配不拆“查看 / 使用 / 执行 / 管理”动作，权限边界参照 AuraOA 的系统管理员 / 租户内管理员分层设计。
- 当前静态卡片页面只是信息层级抽象，生产页面要逐步改造成概览、顶层菜单 / 页签、列表、详情、表单、授权矩阵和审计记录，并保留跨页面的数据勾稽关系。
- 运行态必须清楚展示当前步骤、暂停原因、等待对象、可执行动作和最终交付物。
- 审核、运行监控和运行审计必须分层：业务待办处理暂停恢复，运行监控处理取消 / 重试 / 补偿，运行审计只读展示证据链。
- 图标优先使用 `lucide-react`。
- UI 元素不要互相遮挡，移动端至少可浏览核心信息。

### 12.1. UI 优化设计规范

对于管理台系统管理、租户管理、能力资产等页面的 UI 优化，需严格遵守以下视觉与交互设计规范，确保系统的美观度与统一性：

1. **抽屉（Drawer）替代弹窗（Modal）**：
   - 复杂实体的配置、新增与编辑流（如模型供应商、系统能力、业务角色、分配配置、能力草稿等），必须使用侧边抽屉（`<Drawer>`）替代居中弹窗（`<Modal>`）。
   - 抽屉宽度统一推荐为 `560`（`width={560}`）。
   - 抽屉内容必须使用统一容器结构：内容表单包裹在 `.sys-drawer-section` 类中，底部按钮包裹在 `.sys-drawer-footer` 类中。
   - 所有渲染到 `body` 的 Ant Design 弹层或组件（`Drawer`, `Modal`, `Select` 下拉等），必须显式传递带主题标记的 `rootClassName`（如 `themeMode === "dark" ? "agent-admin-drawer agent-admin-drawer--dark" : "agent-admin-drawer"`），保证深色模式在 portal 挂载点能正确覆盖背景、标题、文本及边框颜色。

2. **卡片列表交互**：
   - 卡片网格布局统一采用 3 列响应式网格布局（使用 `.sys-card-grid`），卡片宽度应跟随页面尺寸自适应，不可写死固定宽度。
   - 卡片需去除 `.sys-card--static` 类，使其处于可悬停交互状态，且卡片整体需绑定 `onClick` 事件以支持点击卡片打开对应的编辑/详情 Drawer。
   - 卡片底部的操作按钮或辅助动作应在 `onClick` 中调用 `e.stopPropagation()` 防止事件冒泡误触发卡片整体点击。

3. **布局去边框化 (Clean Borders)**：
   - 移除所有工具栏/操作栏的圆角灰色边框容器背景（避免使用带灰色背景和边框的工具栏），改为无背景、无边框、平级布局的简洁 Flex 容器。
   - 操作按钮与查询组件统一右对齐，使页面层次更为扁平与现代。

4. **深色模式适配 (Dark Mode)**：
   - 所有全局提示（`message`）、自定义弹窗、下拉选项等容易出现对比度缺失的组件，均须在全局 CSS 中对 `.dark` 类下进行适配与深度覆盖，绝不允许出现白底白字或深底黑字。

## 13. 每次交付要求

- 说明本次属于哪个阶段和任务。
- 同步更新相关文档、契约和测试。
- 新增或修改接口时更新 OpenAPI / JSON Schema。
- 新增或改造管理台列表接口时，同步实现分页参数、分页响应模型和排序白名单校验。
- 新增数据库结构时更新 Flyway 迁移。
- 新增或修改复杂逻辑时检查中文注释是否解释业务原因，检查关键成功 / 失败路径是否有脱敏日志。
- 完成后运行影响范围内的验证。
- 如果无法验证，最终说明必须写明原因和剩余风险。
