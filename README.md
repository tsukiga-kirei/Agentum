# Agentum

Agentum 是一个以智能体为载体的企业工作流平台。它不是把所有业务都塞进一段 AI 聊天，而是把任务拆成清晰的工作步骤：用户在具体节点补充信息、追问确认、审核、回退或暂停，智能体在具体节点调用 Skills、MCP、提示词模板和交付能力，最终形成可审计、可恢复、可交付的业务结果。

## 当前进度

项目当前处于 **阶段一：框架与基础治理**。这一阶段不追求一次性做完所有智能体能力，而是先打通企业工作流平台最关键的地基：多租户身份、角色入口、租户内组织权限、系统能力治理、能力资产草稿发布、流程设计草稿和发布校验。

已具备：

- React + TypeScript 前端工作台，包含登录、角色切换、业务工作台、流程设计、能力资产、租户管理和系统管理入口。
- Spring Boot API 服务，已实现认证、租户、组织、权限、系统管理、能力资产和工作流草稿的第一批接口。
- Flyway 数据库迁移，按 `schema` 与 `devdata` 拆分真实结构和本地演示数据。
- 工作流设计草稿、阶段积木、变量声明、发布校验和不可变发布版本的后端基础能力。
- 系统管理的租户、模型供应商、全局能力、租户可用能力池和租户模型分配能力。
- 租户管理的成员、部门、角色、页签分配和能力分配能力。
- 能力资产的“对我开放 / 我的能力”页面，以及提示词模板草稿、智能体模板草稿的编辑、发布和删除能力。
- PostgreSQL、Redis、RabbitMQ、MinIO、Mailpit 的本地开发 Compose 配置。
- OpenAPI / JSON Schema 的共享契约目录和部分接口契约。

正在推进：

- 补齐工作流运行态：运行实例、节点执行、暂停恢复、变量快照、重试补偿和审计链路。
- 补齐智能体运行时：模型调用、Skill 装配、MCP 网关、输出校验和工具调用审计。
- 补齐交付闭环：文档生成、邮件、OA、IM、Webhook 或数据库写入等交付能力。
- 将共享契约进一步统一为 OpenAPI / JSON Schema 生成前端类型。
- 增加产品运行时示例 Skill、示例 MCP Server 和交付适配器。

详细阶段记录见 [docs/progress/README.md](./docs/progress/README.md)。

## 项目导读

Agentum 的第一版核心闭环可以理解为：

```text
系统管理员登记底层能力
  -> 为租户开放可用能力池和模型
  -> 租户管理员分配成员、页签、资源范围和能力
  -> 业务用户创建或使用智能体 / 提示词能力
  -> 流程设计者搭建阶段积木并发布流程版本
  -> 业务用户发起运行、处理中断点、审核结果并接收交付物
  -> 审计人员追溯运行链路、变量快照、工具调用和交付记录
```

当前代码已经覆盖前半段治理链路，运行态和真实 AI / MCP / 交付链路仍在建设中。

### 角色入口

| 入口 | 典型用户 | 当前能力 |
| --- | --- | --- |
| 业务用户 | 发起人、流程设计者、能力维护者、审核人 | 进入业务工作台、流程设计和能力资产；具体可见内容由租户内权限决定 |
| 租户管理 | 租户管理员 | 管理当前租户成员、部门、角色、页签分配和能力分配 |
| 系统管理 | 平台管理员 | 管理租户、模型供应商、全局能力、租户可用能力池和模型分配 |

登录页只做入口选择和体验分流。后端仍会根据 token、租户、角色、部门、资源范围和能力池重新校验每个请求。

### 核心模块

| 模块 | 前端位置 | 后端包 | 说明 |
| --- | --- | --- | --- |
| 认证与角色上下文 | `apps/web/src/surfaces/auth`、`apps/web/src/stores/authStore.ts` | `com.agentum.auth` | 登录、当前用户、登出、角色切换、菜单返回 |
| 租户与公开入口 | 登录页租户选择 | `com.agentum.tenant` | 公开租户列表和租户基础信息 |
| 租户组织治理 | `apps/web/src/surfaces/admin/TenantManagementPage.tsx` | `com.agentum.organization`、`com.agentum.permission` | 成员、部门、租户内角色、页签授权、能力授权 |
| 系统管理 | `apps/web/src/surfaces/admin/SystemManagementPage.tsx` | `com.agentum.system` | 平台租户、模型供应商、全局能力、租户能力池和模型分配 |
| 能力资产 | `apps/web/src/surfaces/assets/AssetsPage.tsx` | `com.agentum.asset` | 对我开放的能力、我的能力草稿、提示词模板和智能体模板发布 |
| 流程设计 | `apps/web/src/surfaces/designer` | `com.agentum.workflow` | 流程草稿、阶段积木、变量声明、发布校验和版本冻结 |
| 共享响应与分页 | `apps/web/src/services/apiClient.ts` | `com.agentum.shared` | 统一 API 响应、错误结构、requestId 和分页组件 |

### 能力与契约

- `packages/shared-contract/` 保存 OpenAPI、JSON Schema 和事件契约，是前后端协议的长期来源。
- `capabilities/` 保存产品运行时能力源码，不等同于数据库里的资产台账。
- `capabilities/skills/` 用于产品运行时 Skill，`.codex/skills/` 只服务本仓库开发辅助，不能直接发布为 Agentum 产品能力。
- 能力进入真实运行前需要经过系统管理登记、租户可用能力池开放、租户内分配和运行时审计。

## 文档

长期主文档只维护三份：

- [开发规范](./docs/development-standards.md)：开发约束、代码风格、测试要求、接口规范和当前任务入口。
- [系统详细梳理介绍](./docs/system-overview.md)：产品定位、角色视角、核心工作流、能力资产、权限和运行态说明。
- [架构文档](./docs/architecture.md)：技术栈、模块边界、数据模型、数据库版本管理和部署演进。

当前执行状态与后续计划单独维护：

- [当前进度与后续计划](./docs/progress/README.md)

## 推荐技术栈

```text
前端：React + TypeScript + Tailwind CSS + Ant Design
后端：Java 21 + Spring Boot
数据库：PostgreSQL + Flyway
缓存：Redis
队列：RabbitMQ
文件存储：S3 兼容对象存储 / MinIO
Worker：Java Worker 优先，复杂文档或 AI 辅助任务可补 Python Worker
契约：OpenAPI + JSON Schema
```

## 本地开发

安装依赖：

```bash
pnpm install
```

启动本地基础设施：

```bash
make dev-infra
```

启动后端 API：

```bash
./gradlew :apps:api:bootRun
```

启动前端：

```bash
pnpm dev:web
```

关闭本地基础设施：

```bash
make down-infra
```

常用验证：

```bash
pnpm lint:web
pnpm build:web
./gradlew test
```

本地基础设施默认端口：

| 服务 | 地址 |
| --- | --- |
| Web | `localhost:5173` |
| API | `localhost:8080` |
| PostgreSQL | `localhost:5432` |
| Redis | `localhost:6379` |
| RabbitMQ | `localhost:5672`，管理台 `localhost:15672` |
| MinIO | `localhost:9000`，控制台 `localhost:9001` |
| Mailpit | SMTP `localhost:1025`，控制台 `localhost:8025` |

### 本地演示账号

`local` profile 会加载 `db/migration/devdata` 中的演示数据。初始密码统一为 `agentum123`。

| 用户名 | 入口 | 租户 |
| --- | --- | --- |
| `admin` | 系统管理 | 不绑定租户 |
| `operator` | 业务用户 | 云程科技 |
| `designer` | 业务用户 | 云程科技 |
| `tenantadmin` | 租户管理 | 云程科技 |

## 目录结构

```text
apps/web                  前端应用
apps/api                  后端 API 服务
packages/shared-contract  OpenAPI、JSON Schema 和事件契约
packages/ui               前端通用 UI 包
capabilities              产品运行时能力源码与自研连接器
capabilities/skills       产品运行时 Skill 定义、说明、测试和发布材料
capabilities/mcp-servers  自研 MCP Server 实现
capabilities/prompt-templates 提示词模板源码
capabilities/delivery     交付能力适配器、模板和脚本
workers/document-worker   文档处理 Worker
workers/ai-worker         AI 辅助任务 Worker
deploy                    Docker、Nginx、本地和部署配置
scripts                   辅助脚本
docs                      项目文档
```

## 贡献

欢迎通过 Pull Request 一起推进。建议 PR 保持小步提交，说明变更背景、影响范围和验证结果；涉及权限、状态机、变量解析、MCP 调用、模型输出解析、审计、重试和交付能力时，请同步补测试或说明剩余风险。

## License

本项目计划采用 MIT License。许可证正文见 [LICENSE](./LICENSE)。
