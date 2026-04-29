# Agentum

Agentum 是一个面向企业 SOP 场景的智能体装配式工作流平台。

当前仓库处于项目初始化阶段，重点先沉淀文档、工程骨架、配置规范和本地开发基础设施。

## 文档入口

- [项目说明与实现规范](./docs/README.md)
- [系统架构](./docs/architecture.md)
- [产品界面分区](./docs/product-surfaces.md)
- [技术栈建议](./docs/technology-stack.md)
- [工作流引擎设计](./docs/workflow-engine.md)
- [AI 交互运行设计](./docs/ai-interaction-runtime.md)
- [权限模型](./docs/permission-model.md)
- [前端设计规范](./docs/frontend-guidelines.md)
- [前端工作流展示规范](./docs/frontend-workflow-visualization.md)
- [开发规范](./docs/development-standards.md)
- [Skills 与 MCP 推荐](./docs/skills-and-mcp.md)
- [项目结构说明](./docs/project-structure.md)

## 推荐技术路线

```text
前端：React + TypeScript + React Flow
后端：Java 21 / Kotlin + Spring Boot
数据库：PostgreSQL
缓存：Redis
队列：RabbitMQ / Kafka
文件存储：S3 兼容对象存储
Worker：Java / Python 按任务类型拆分
```

## 本地开发命令

当前仓库只提供工程骨架和基础配置，业务实现会在后续补充。

常用命令：

```bash
make dev-infra
make down-infra
pnpm dev:web
```

等价命令：

```bash
docker compose -f docker-compose.dev.yml up -d
docker compose -f docker-compose.dev.yml down
pnpm --filter @agentum/web dev
```

本地基础设施包含：

- PostgreSQL：`localhost:5432`
- Redis：`localhost:6379`
- RabbitMQ：`localhost:5672`，管理台 `localhost:15672`
- MinIO：`localhost:9000`，控制台 `localhost:9001`
- Mailpit：SMTP `localhost:1025`，控制台 `localhost:8025`

## 当前目录

```text
apps/web                  前端应用
apps/api                  后端 API 服务
packages/shared-contract  共享协议与 Schema
packages/ui               前端通用 UI 包
workers/document-worker   文档处理 Worker
workers/ai-worker         AI 任务 Worker
deploy                    部署与 Docker 相关配置
scripts                   辅助脚本目录
docs                      项目文档
```

## 构建与配置

- 前端使用 `pnpm workspace` 管理。
- 后端与 Java Worker 使用 Gradle Kotlin DSL，也就是 `build.gradle.kts` 和 `settings.gradle.kts`。
- Docker 开发环境使用 [docker-compose.dev.yml](./docker-compose.dev.yml)。
- 生产/集成环境的 Compose 骨架放在 [docker-compose.yml](./docker-compose.yml)。
