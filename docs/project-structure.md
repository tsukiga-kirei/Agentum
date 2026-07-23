# 项目目录说明

本文档说明 Agentum 仓库的目录分层、根目录文件职责，以及各主要子目录应放置什么内容。架构边界与模块演进原则另见 [架构文档](./architecture.md)；日常开发常用路径见 [AGENTS.md](../AGENTS.md)。

## 1. 仓库总览

Agentum 采用 **pnpm 前端 monorepo + Gradle 多模块后端** 的单仓库结构：

```text
agentum/
├── apps/                   可运行应用（前端 web、后端 api）
├── packages/               跨端共享契约（OpenAPI、JSON Schema）
├── capabilities/           产品运行时能力源码（Skill、MCP、自定义交付）
├── workers/                长耗时 Worker（文档、AI 辅助任务）
├── deploy/                 Docker、Nginx、本地基础设施配置
├── docs/                   产品、架构、进度与界面截图
├── scripts/                辅助脚本占位
├── apps 之外的构建与编排文件   Gradle、Docker Compose、pnpm 工作区配置
└── 根目录配置文件             见下文「根目录文件说明」
```

阅读顺序建议：

1. 本文档 — 知道「文件放哪里」
2. [架构文档](./architecture.md) — 知道「模块如何边界划分」
3. [开发规范](./development-standards.md) — 知道「命名与接口约定」

---

## 2. 根目录文件说明

下表列出**仓库根目录**下的文件（不含子目录）。带 ※ 的文件通常不应提交敏感内容或本地产物。

| 文件 | 类型 | 说明 |
| --- | --- | --- |
| [README.md](../README.md) | 文档 | 项目入口：产品定位、快速开始、部署概要、文档索引 |
| [AGENTS.md](../AGENTS.md) | 文档 | AI 代理与协作者开发入口：必读文档、常用路径、验证命令 |
| [LICENSE](../LICENSE) | 许可 | MIT 许可证 |
| [Makefile](../Makefile) | 构建 | 本地快捷命令：`make dev-infra`、`make down-infra`、`make dev-web`、`make build-web` |
| [package.json](../package.json) | Node | pnpm 工作区根包：统一脚本 `dev:web`、`build:web`、`lint:web`、`dev:infra` |
| [pnpm-workspace.yaml](../pnpm-workspace.yaml) | Node | 声明 pnpm 工作区成员（当前为 `apps/web`） |
| [pnpm-lock.yaml](../pnpm-lock.yaml) | Node | 前端依赖锁定文件，提交后保证可复现安装 |
| [build.gradle.kts](../build.gradle.kts) | Gradle | 根构建脚本：Java 21 工具链、Spring Boot 插件版本 |
| [settings.gradle.kts](../settings.gradle.kts) | Gradle | Gradle 模块注册：`apps:api`、`workers:document-worker` |
| [gradlew](../gradlew) | Gradle | Unix Gradle Wrapper 启动脚本 |
| [gradlew.bat](../gradlew.bat) | Gradle | Windows Gradle Wrapper 启动脚本 |
| [docker-compose.yml](../docker-compose.yml) | 部署 | 测试 / 正式环境 Compose：加载已构建的 `agentum-api` / `agentum-web` 镜像 |
| [docker-compose.dev.yml](../docker-compose.dev.yml) | 部署 | 本地开发基础设施：PostgreSQL、Redis、RabbitMQ、MinIO、Mailpit |
| [.env.example](../.env.example) | 配置样例 | Docker 部署环境变量模板；复制为 `.env` / `.env.test` / `.env.prod` 后修改 |
| [.env](../.env) ※ | 本地配置 | 本机真实环境变量，**已在 `.gitignore` 中忽略，勿提交** |
| [.gitignore](../.gitignore) | Git | 忽略 `node_modules`、`build/`、`dist/`、`.env`、IDE 目录等 |
| [.dockerignore](../.dockerignore) | Docker | 构建镜像时排除 `.git`、`node_modules`、本地 `.env` 等 |
| [.editorconfig](../.editorconfig) | 格式 | 统一 UTF-8、换行符、缩进（前端 2 空格，Java/Kotlin 4 空格） |
| [.prettierrc](../.prettierrc) | 格式 | Prettier 规则（行宽 100、双引号、尾逗号） |
| [.prettierignore](../.prettierignore) | 格式 | Prettier 跳过的目录（`node_modules`、`dist`、`build` 等） |
| [.nvmrc](../.nvmrc) | Node | 推荐 Node 版本（当前 `22`） |

### 2.1 根目录常见文件夹（非文件）

| 目录 | 是否入库 | 说明 |
| --- | --- | --- |
| `apps/` | 是 | 前端与后端应用源码 |
| `packages/` | 是 | 共享契约 |
| `capabilities/` | 是 | 产品运行时能力源码 |
| `workers/` | 是 | Worker 模块 |
| `deploy/` | 是 | 部署相关配置 |
| `docs/` | 是 | 文档与 `docs/images/` 产品截图 |
| `scripts/` | 是 | 辅助脚本（当前仅 README 占位） |
| `gradle/` | 是 | Gradle Wrapper 发行包 |
| `artifacts/` | 视情况 | 开发过程截图或对比图，非正式文档资产 |
| `dist/` ※ | 否 | Docker 镜像导出等构建产物 |
| `build/` ※ | 否 | Gradle 根级构建输出 |
| `node_modules/` ※ | 否 | pnpm 依赖 |
| `.gradle/`、`.idea/`、`.pnpm-store/` ※ | 否 | 本地工具与缓存 |

---

## 3. apps/ — 可运行应用

```text
apps/
  web/          React + Vite 前端工作台
  api/          Spring Boot 后端 API
```

### 3.1 apps/web — 前端

| 路径 | 职责 |
| --- | --- |
| `src/App.tsx` | 应用根组件，挂载路由 |
| `src/main.tsx` | Vite 入口 |
| `src/routes/` | 路由表、`paths` 常量、`ProtectedRoute` / `MenuGuard` 等守卫 |
| `src/surfaces/` | **页面级模块**，按产品区域组织（与左侧菜单/入口对应） |
| `src/features/` | **领域特性模块**占位与逐步沉淀区（工作流、运行态、资产等） |
| `src/components/` | 可复用 UI 组件（运行态面板、文档预览、Cron、品牌元素等） |
| `src/layouts/` | 壳层布局（如 `AppLayout` 顶栏 + 侧栏） |
| `src/services/` | API 客户端（`apiClient.ts` 及各域 API 封装） |
| `src/stores/` | Zustand 状态（`authStore`、会话偏好等） |
| `src/hooks/` | 共享 Hook（如 `useRunStream` SSE 运行流） |
| `src/motion/` | GSAP 动效封装（登录品牌、工作台页头字/翻转、模块描述翻转；尊重 reduced-motion） |
| `src/types/` | TypeScript 类型（部分待 OpenAPI 生成收口） |
| `src/utils/`、`src/lib/` | 工具函数与轻量库封装 |
| `src/constants/` | 前端常量 |
| `src/styles/` | 全局样式、主题变量 |
| `public/` | 静态资源（favicon、品牌图） |
| `index.html` | Vite HTML 模板 |
| `vite.config.ts` | 开发服务器、API 代理、构建分包 |
| `tailwind.config.ts` | Tailwind 配置 |
| `eslint.config.js` | ESLint 规则 |
| `.env.example` | 前端环境变量样例（如 API 基址） |

#### surfaces/ 页面区域

| 子目录 | 对应产品区域 | 主要页面 |
| --- | --- | --- |
| `auth/` | 认证 | `LoginPage`、`SetupPage`（首次部署初始化） |
| `workbench/` | 业务工作台 | `WorkbenchShell`（待办、发起、运行详情、定时任务） |
| `designer/` | 流程设计 | `WorkflowDraftsPage`、`WorkflowEditorPage` |
| `assets/` | 能力资产 | `AssetsPage` |
| `audit/` | 运行审计 | `AuditPage`、工具调用 / 操作日志页签 |
| `admin/` | 管理台 | `SystemManagementPage`、`TenantManagementPage` |

约定：`surfaces` 负责**路由直达的页面组合**；复杂交互逻辑优先沉到 `components/` 或 `features/`，避免页面文件无限膨胀。

#### components/ 主要分组

| 子目录 | 内容 |
| --- | --- |
| `runtime/` | 运行态：智能体输出、追问、交付预览、Excel/Word 预览、步骤操作栏 |
| `workbench/` | 工作台列表、任务卡片、权限面板等 |
| `document/` | 文档渲染相关 |
| `cron/` | Cron 表达式生成与展示 |
| `common/` | 通用小组件 |
| `brand/` | Logo、品牌标记 |

### 3.2 apps/api — 后端

| 路径 | 职责 |
| --- | --- |
| `src/main/java/com/agentum/AgentumApiApplication.java` | Spring Boot 启动类 |
| `src/main/java/com/agentum/config/` | 横切配置：Security、认证过滤器、可注入 `Clock` |
| `src/main/java/com/agentum/shared/` | 横切能力：统一 API 响应、分页、加解密工具、客户端断开支持 |
| `src/main/resources/application*.yml` | Spring 配置（数据源、Redis、RabbitMQ、运行态超时、可选 `logfile` 文件日志等） |
| `src/main/resources/logback-spring.xml` | 系统 / 租户运行日志分流、滚动与格式配置 |
| `src/main/resources/db/migration/` | Flyway 迁移，见下文 |
| `src/test/java/` | 单元与集成测试（与 `main` 包结构对应） |
| `build.gradle.kts` | API 模块依赖与测试配置 |

#### 数据库迁移

```text
apps/api/src/main/resources/db/
  migration/
    schema/     真实表结构、索引、约束（所有环境执行）
    devdata/    本地演示账号、租户、能力（仅 local profile 加载）
  README.md     迁移规范与演示账号说明
```

#### 后端业务包（`com.agentum.*`）

各业务包通常按四层组织：

```text
<module>/
  application/      应用服务、用例编排、校验
  domain/           实体、枚举、领域规则
  infrastructure/   JPA 仓储、外部系统适配
  interfaces/       REST Controller、请求/响应 DTO
```

| 包名 | 职责 |
| --- | --- |
| `auth` | 登录、登出、刷新 Token、角色切换、SSO、引导初始化 |
| `tenant` | 租户公开列表、租户级查询 |
| `organization` | 成员、部门、角色、页签分配、能力分配卡片 |
| `permission` | 权限策略、资源范围判权 |
| `system` | 系统管理：租户、模型供应商、全局能力、租户能力池 |
| `asset` | 能力资产：智能体模板、提示词模板草稿发布与授权 |
| `workflow` | 工作流草稿、变量、发布校验、版本快照、设计目录 |
| `workbench` | 业务工作台摘要、运行实例、待办推进 |
| `agent` | 智能体运行时：模型调用、ReAct、Skill/MCP 工具装配 |
| `runtime` | 异步执行：RabbitMQ 命令、Redis 租约/流、取消与回收 |
| `mcp` | MCP 网关：连通性测试、工具发现、`tools/call` |
| `delivery` | 交付：邮件、Webhook、Word、Excel、文件存储 |
| `attachment` | 输入附件：系统识别配置、上传保存、本地 / MinerU 解析、预览下载与保留期清理 |
| `audit` | 运行审计查询、证据聚合 |
| `schedule` | 流程定时任务与执行记录 |
| `notification` | 站内通知与消息中心 |

说明：模型供应商配置在 `system`，模型调用在 `agent`；Skill 登记与读取贯穿 `system` / `agent` / `asset`。阶段二「知识资产」落地时将新增独立业务包，不在此提前占位。

---

## 4. packages/ — 共享契约

`packages/shared-contract` 是前后端、运行态与 Worker 共同遵守的**协议单一事实来源**（Single Source of Truth）。代码可以分在 `apps/web` 和 `apps/api`，但字段叫什么、类型是什么、接口路径与分页格式，应优先在这里定义，避免两边各写一套逐渐漂移。

详见 [packages/shared-contract/README.md](../packages/shared-contract/README.md)。

```text
packages/shared-contract/
  openapi/
    agentum.openapi.yaml       REST API 主契约（持续补齐中）
  schemas/
    workflow-node.schema.json  工作流节点配置结构
    variable.schema.json       变量声明
    agent.schema.json          智能体模板
    mcp.schema.json            MCP 服务描述
    prompt-template.schema.json
    delivery-capability.schema.json
    tenant-capability-grant.schema.json
  events/
    node-execute-command.schema.json   节点执行命令（API → Worker / 运行时）
    runtime-events.schema.json         运行进度 SSE / 流式事件
  README.md
```

### 4.1 三类契约各自解决什么问题

| 类型 | 文件 | 作用 | 谁消费 |
| --- | --- | --- | --- |
| **OpenAPI** | `openapi/agentum.openapi.yaml` | 描述 HTTP API：路径、请求体、响应体、错误结构、分页参数 | 前端 `apiClient`、后端 Controller/DTO 对齐、后续可生成 TS 客户端 |
| **JSON Schema** | `schemas/*.schema.json` | 描述**持久化与跨模块复用**的领域对象：工作流节点、变量、智能体、MCP、交付能力等 | 发布校验、导入导出、流程快照、与外部系统交换配置 |
| **事件 Schema** | `events/*.schema.json` | 描述**异步消息**形态：节点执行命令、运行态进度事件 | RabbitMQ 消费、Redis Stream / SSE 推送、Worker 入参 |

### 4.2 为什么需要共享契约

没有契约时，常见问题是：

- 后端改了字段名，前端类型没改，编译通过但运行时报错；
- 工作流发布快照里的节点 JSON 与编辑器保存的结构不一致；
- 运行态 SSE 事件多一个字段，前端 Hook 静默忽略，界面状态错乱。

共享契约的目标是：**先改契约，再改实现**，让变更有据可查、可测试、可生成代码。

### 4.3 当前落地状态

- OpenAPI 已覆盖认证、系统管理、租户组织、工作流、工作台、审计等大量路径，仍在持续补齐。
- 前端 `apps/web/src/types/` 中仍有部分手写类型（如 `workbench.ts`），规范要求逐步改为从 OpenAPI / Schema 生成。
- 后端 DTO 与契约不一致时，以契约 + 测试为准修正实现。

### 4.4 变更流程（开发时）

1. 在 `openapi/` 或 `schemas/` / `events/` 中修改或新增定义；
2. 同步修改后端 `interfaces` DTO 与校验逻辑；
3. 同步修改前端类型与 API 调用；
4. 补或更新相关测试；
5. 若影响产品语义，更新 `docs/` 中对应说明。

原则：**接口变更先改契约，再改后端与前端**；工作流节点、变量、智能体、MCP、提示词模板、交付能力等核心结构必须版本化、可回放。

---

## 5. capabilities/ — 产品运行时能力源码

详见 [capabilities/README.md](../capabilities/README.md)。

```text
capabilities/
  skills/                  产品 Skill 说明、约束、样例（如 agentum-connectivity-check）
  mcp-servers/             自研 MCP Server 源码与 manifest（如 agentum-test-mcp）
  delivery/                自定义交付适配器（系统内置邮件/Word/Excel 在 API 内实现）
    custom-oa-delivery/    示例：OA 交付适配器占位
```

边界：

- 这里是**可执行实现**与本地测试材料；
- 登记、版本、租户能力池、分配与审计在 `apps/api` 的 `system` / `asset` / `organization`；
- `.codex/skills/`（若存在）是开发辅助技能，**不属于**产品运行时发布链路。

---

## 6. workers/ — 长耗时任务

```text
workers/
  document-worker/     Spring Boot Worker（Gradle 子模块，settings.gradle.kts 已注册）
  ai-worker/           Python Worker 占位（复杂模型/文档解析扩展）
```

当前主业务仍在 `apps/api` 内同步或经 RabbitMQ 消费；Worker 用于后续 Word 大文档、批处理等拆分。

---

## 7. deploy/ — 部署与本地配置

```text
deploy/
  docker/
    api.Dockerfile
    web.Dockerfile
    document-worker.Dockerfile
    ai-worker.Dockerfile
  nginx/
    default.conf           前端静态资源 + API 反代
  local/
    postgres/init/         本地 Postgres 初始化脚本占位
  k8s/                     Kubernetes 占位（后续 Helm/YAML）
  README.md
```

配套根目录 Compose 文件：

- `docker-compose.dev.yml` — 仅基础设施，本地 `make dev-infra` 使用
- `docker-compose.yml` — 引用已构建镜像的完整部署栈

---

## 8. docs/ — 文档

| 路径 | 说明 |
| --- | --- |
| [system-overview.md](./system-overview.md) | 产品定位、角色、区域划分 |
| [architecture.md](./architecture.md) | 架构目标、模块边界、数据与部署演进 |
| [development-standards.md](./development-standards.md) | 命名、接口、错误码、测试规范 |
| [project-structure.md](./project-structure.md) | **本文档** |
| [progress/README.md](./progress/README.md) | 阶段进度与任务计划 |
| [capability-workflow-governance.md](./capability-workflow-governance.md) | 能力—流程—权限治理 |
| [ai-runtime-integration.md](./ai-runtime-integration.md) | AI 运行态接入 |
| [skill-mcp-runtime-guide.md](./skill-mcp-runtime-guide.md) | Skill 与 MCP 运行机制 |
| [runtime-async-execution-design.md](./runtime-async-execution-design.md) | MQ + Redis 异步执行 |
| [word-document-delivery.md](./word-document-delivery.md) | Word 交付 |
| [excel-workbook-delivery.md](./excel-workbook-delivery.md) | Excel 交付 |
| [sso-integration.md](./sso-integration.md) | 企业 SSO |
| [oa-basic-sso-integration.md](./oa-basic-sso-integration.md) | OA Basic 单点登录对接示例 |
| `images/` | README 引用的产品界面截图 |

---

## 9. scripts/

存放代码生成、契约同步、数据库工具等辅助脚本。当前仅有 README 占位；新增脚本时请在本目录 README 补充用途与示例命令。

---

## 10. 完整目录树（精简版）

以下为**源码与配置**为主的树形参考，省略 `node_modules/`、`build/`、`dist/`、`.gradle/` 等本地/构建产物。

```text
agentum/
├── AGENTS.md
├── LICENSE
├── Makefile
├── README.md
├── build.gradle.kts
├── docker-compose.dev.yml
├── docker-compose.yml
├── gradle/wrapper/
├── gradlew
├── gradlew.bat
├── package.json
├── pnpm-lock.yaml
├── pnpm-workspace.yaml
├── settings.gradle.kts
├── apps/
│   ├── api/
│   │   └── src/
│   │       ├── main/java/com/agentum/
│   │       │   ├── AgentumApiApplication.java
│   │       │   ├── config/
│   │       │   ├── shared/
│   │       │   ├── auth/
│   │       │   ├── tenant/
│   │       │   ├── organization/
│   │       │   ├── permission/
│   │       │   ├── system/
│   │       │   ├── asset/
│   │       │   ├── workflow/
│   │       │   ├── workbench/
│   │       │   ├── agent/
│   │       │   ├── runtime/
│   │       │   ├── mcp/
│   │       │   ├── delivery/
│   │       │   ├── attachment/
│   │       │   ├── audit/
│   │       │   ├── schedule/
│   │       │   └── notification/
│   │       ├── main/resources/
│   │       │   ├── application*.yml
│   │       │   ├── logback-spring.xml
│   │       │   └── db/migration/{schema,devdata}/
│   │       └── test/java/com/agentum/
│   └── web/
│       ├── public/
│       ├── src/
│       │   ├── App.tsx
│       │   ├── routes/
│       │   ├── surfaces/{auth,workbench,designer,assets,audit,admin}/
│       │   ├── features/{workflow,runs,assets,agent,permission,audit}/
│       │   ├── components/{runtime,workbench,document,cron,common,brand}/
│       │   ├── layouts/
│       │   ├── services/
│       │   ├── stores/
│       │   ├── hooks/
│       │   ├── types/
│       │   ├── utils/
│       │   ├── lib/
│       │   ├── constants/
│       │   └── styles/
│       ├── index.html
│       └── vite.config.ts
├── packages/shared-contract/
│   ├── openapi/
│   ├── schemas/
│   └── events/
├── capabilities/
│   ├── skills/
│   ├── mcp-servers/
│   └── delivery/
├── workers/
│   ├── document-worker/
│   └── ai-worker/
├── deploy/
│   ├── docker/
│   ├── nginx/
│   ├── local/
│   └── k8s/
├── docs/
│   ├── images/
│   ├── progress/
│   └── *.md
└── scripts/
    └── README.md
```

---

## 11. 新增代码时应放在哪里

| 你要做的事 | 推荐位置 |
| --- | --- |
| 新增业务页面 / 菜单区域 | `apps/web/src/surfaces/<区域>/` |
| 新增可复用 UI | `apps/web/src/components/` |
| 新增前端 API 调用 | `apps/web/src/services/apiClient.ts` 或域内封装 |
| 新增 REST 接口 | `apps/api/.../interfaces/` + `application/` |
| 新增表或字段 | `db/migration/schema/V*.sql` |
| 新增本地演示数据 | `db/migration/devdata/V*.sql` |
| 更新接口契约 | `packages/shared-contract/openapi/` 或 `schemas/` |
| 新增自研 MCP | `capabilities/mcp-servers/<name>/` |
| 新增产品 Skill | `capabilities/skills/<name>/` |
| 新增自定义交付适配器 | `capabilities/delivery/<name>/` |
| 新增部署配置 | `deploy/` + 根目录 Compose / Dockerfile |
| 新增长期设计文档 | `docs/`（阶段进度写入 `docs/progress/`） |
| 阶段二知识资产 | 落地时新增 `com.agentum.knowledge` 等业务包，并同步契约与迁移 |

---

## 12. 维护说明

- 目录发生结构性变化（新增顶层目录、前后端分层调整、Worker 启用）时，请同步更新本文档与 [README.md](../README.md) 目录索引。
