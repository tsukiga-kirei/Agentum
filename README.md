<div align="center">

# Agentum

**AI 驱动的企业智能体工作流平台**  
*— Agentic workflow platform that combines AI execution with clear, governable enterprise process steps.*

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21+-orange.svg)](https://openjdk.org/)
[![React](https://img.shields.io/badge/React-18+-61DAFB.svg)](https://react.dev/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-6DB33F.svg)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-336791.svg)](https://www.postgresql.org/)
[![Docker Compose](https://img.shields.io/badge/Docker-Compose-2496ED.svg)](https://docs.docker.com/compose/)

</div>

---

## 项目简介

Agentum 是一个以智能体为执行单元的企业工作流平台。它面向「任务主体交给 AI、流程边界交给企业」这一协作方式：企业先把 SOP 整理成清晰可理解的流程步骤，智能体在每一步的具体上下文里调用 Skill、MCP、提示词模板和模型能力；业务同学在输入、追问、审核等节点介入，最终形成可暂停、可恢复、可审计、可交付的业务结果。

与一次性对话式 AI 不同，Agentum 强调步骤化推进、责任节点和运行留痕；与面向个人的可视化编排工具不同，它从第一版内建多租户、组织权限、能力池治理和发布校验，让 AI 能力真正进入企业日常流程，而不是停留在演示级编排。

当前处于**阶段一：框架与基础治理**。身份、租户、组织权限、能力资产、流程设计草稿和业务工作台运行态已打通；后端运行态采用 Agent ReAct 模式，支持模型自主调用 Skill / MCP 工具并通过 SSE 输出 Markdown `final_answer`，同时保留交付记录、变量快照和模型/MCP 调用留痕。异步 Worker、复杂文档生成、高风险审批、追问追加上下文和完整运行审计页仍在建设中。详见 [当前进度](./docs/progress/README.md)。

---

## 核心能力

- **多租户与角色入口** — 业务用户、租户管理、系统管理三类登录入口，后端按租户、角色、资源范围与能力池复核每个请求
- **阶段积木流程设计** — 以步骤组织流程，支持变量声明、发布校验与不可变版本快照
- **能力资产治理** — 智能体模板、Skills、MCP、提示词模板与交付能力统一登记、版本化与分配
- **能力池分配模型** — 系统管理配置租户可用能力池，租户管理分配至成员、部门与角色
- **智能体节点运行时** — 节点内以 ReAct/Function Calling 方式调用模型、Skill 和 MCP，支持单智能体与多智能体集群流式执行
- **人工卡点与交付** — 输入、审核、暂停恢复，当前支持站内交付记录、邮箱和 Webhook；Word / PDF、OA、IM 适配继续建设
- **运行审计** — 追溯运行链路、变量快照、模型调用、MCP 调用与交付记录；独立审计页和补偿操作继续建设

---

## 技术栈

| 层 | 技术 |
| --- | --- |
| 前端 | React、TypeScript、Vite、Tailwind CSS、Ant Design、Zustand |
| 后端 | Java 21、Spring Boot、Spring Security、Spring Data JPA |
| 数据 | PostgreSQL、Flyway |
| 中间件 | Redis、RabbitMQ、MinIO（S3 兼容） |
| 契约 | OpenAPI、JSON Schema（`packages/shared-contract`） |
| Worker | Java Worker 优先；文档生成、AI 重任务可补 Python Worker |
| 本地环境 | Docker Compose（PostgreSQL、Redis、RabbitMQ、MinIO、Mailpit） |

---

## 快速开始

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

关闭基础设施：

```bash
make down-infra
```

常用验证：

```bash
pnpm lint:web
pnpm build:web
./gradlew test
```

### 默认端口

| 服务 | 地址 |
| --- | --- |
| Web | `http://localhost:5173` |
| API | `http://localhost:8080` |
| PostgreSQL | `localhost:5432` |
| Redis | `localhost:6379` |
| RabbitMQ | `localhost:5672`（管理台 `15672`） |
| MinIO | `localhost:9000`（控制台 `9001`） |
| Mailpit | SMTP `1025`（控制台 `8025`） |

### 演示账号

`local` profile 加载 `db/migration/devdata`，初始密码均为 **`agentum123`**。

| 用户名 | 入口 | 租户 |
| --- | --- | --- |
| `admin` | 系统管理 | 不绑定 |
| `operator` | 业务用户 | 云程科技 |
| `designer` | 业务用户 | 云程科技 |
| `tenantadmin` | 租户管理 | 云程科技 |

---

## 目录结构

```text
apps/web                  前端工作台
apps/api                  后端 API
packages/shared-contract  OpenAPI、JSON Schema 与事件契约
packages/ui               前端通用 UI
capabilities/             产品运行时能力源码（Skill、MCP、交付适配器）
workers/                  文档 / AI 等 Worker
deploy/                   部署与本地配置
docs/                     产品与架构文档
```

---

## 文档

| 文档 | 说明 |
| --- | --- |
| [系统介绍](./docs/system-overview.md) | 产品定位、角色视角、工作流与能力资产 |
| [架构文档](./docs/architecture.md) | 模块边界、数据模型、部署演进 |
| [开发规范](./docs/development-standards.md) | 命名、接口、测试与 AI 协作约定 |
| [能力—流程—权限治理](./docs/capability-workflow-governance.md) | 版本模型、引用勾稽、收回/删除与后续选型 |
| [AI 运行态接入说明](./docs/ai-runtime-integration.md) | 模型、MCP、Skill、提示词模板与流程运行时的当前实现 |
| [企业 SSO 对接说明](./docs/sso-integration.md) | OIDC 单点登录边界、业务系统配合方式与当前实现状态 |
| [当前进度](./docs/progress/README.md) | 阶段计划与施工状态 |
| [AGENTS.md](./AGENTS.md) | 仓库内 AI 代理开发入口 |

---

## 贡献

欢迎通过 Pull Request 参与。建议小步提交，说明变更背景、影响范围与验证结果；涉及权限、状态机、变量解析、MCP / 模型调用、审计或交付能力时，请同步补测试或注明剩余风险。

## License

本项目采用 [MIT License](./LICENSE)。
