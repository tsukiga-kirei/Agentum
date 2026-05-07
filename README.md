# Agentum

Agentum 是一个以智能体为载体的企业工作流平台。它不是把所有业务都塞进一段 AI 聊天，而是把任务拆成清晰的工作步骤：用户在具体节点补充信息、追问确认、审核、回退或暂停，智能体在具体节点调用 Skills、MCP、提示词模板和交付能力，最终形成可审计、可恢复、可交付的业务结果。

## 当前进度

项目当前处于早期框架阶段，重点是先把产品心智、文档结构、前端工作台骨架、基础权限设计和数据库迁移路线稳定下来。

已具备：

- React + TypeScript 前端工作台骨架。
- 登录页、租户选择、业务工作台、流程设计、能力资产、运行审计、权限管理的静态演示入口。
- Spring Boot API 服务骨架。
- PostgreSQL、Redis、RabbitMQ、MinIO、Mailpit 的本地开发 Compose 配置。
- OpenAPI / JSON Schema 的共享契约占位。

正在推进：

- 文档收敛为三份长期主文档。
- 当前进度和后续任务独立记录到 `docs/progress/`。
- 去掉知识库相关第一阶段范围，优先沉淀智能体模板、Skills、MCP、提示词模板和交付能力。
- 补齐 `capabilities/` 能力源码目录，用于存放产品运行时 Skills、自研 MCP Server、提示词模板和交付适配实现。
- 先补齐租户、用户、部门、角色、权限策略、模型配置和交付能力的基础框架。

详细阶段记录见 [docs/progress/README.md](./docs/progress/README.md)。

## 文档

长期主文档只维护三份：

- [开发规范](./docs/development-standards.md)：开发约束、代码风格、测试要求、接口规范和当前任务入口。
- [系统详细梳理介绍](./docs/system-overview.md)：产品定位、角色视角、核心工作流、能力资产、权限和运行态说明。
- [架构文档](./docs/architecture.md)：技术栈、模块边界、数据模型、数据库版本管理和部署演进。

当前执行状态与后续计划单独维护：

- [当前进度与后续计划](./docs/progress/README.md)

## 推荐技术栈

```text
前端：React + TypeScript + React Flow + Tailwind CSS + Ant Design
后端：Java 21 + Spring Boot
数据库：PostgreSQL + Flyway
缓存：Redis
队列：RabbitMQ
文件存储：S3 兼容对象存储 / MinIO
Worker：Java Worker 优先，复杂文档或 AI 辅助任务可补 Python Worker
契约：OpenAPI + JSON Schema
```

## 本地开发

启动本地基础设施：

```bash
make dev-infra
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
| PostgreSQL | `localhost:5432` |
| Redis | `localhost:6379` |
| RabbitMQ | `localhost:5672`，管理台 `localhost:15672` |
| MinIO | `localhost:9000`，控制台 `localhost:9001` |
| Mailpit | SMTP `localhost:1025`，控制台 `localhost:8025` |

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
