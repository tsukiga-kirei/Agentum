# 项目结构说明

## 1. 结构目标

当前工程骨架用于提前固定 Agentum 的长期边界，避免后期功能增长后目录混乱。

重点覆盖：

- 前端应用
- 后端 API
- 共享契约
- UI 组件库
- 文档处理 Worker
- AI Worker
- Docker 与本地开发环境
- 部署配置
- 项目文档

## 2. 根目录

```text
Agentum/
  apps/
  packages/
  workers/
  deploy/
  scripts/
  docs/
  docker-compose.dev.yml
  docker-compose.yml
  Makefile
  build.gradle.kts
  settings.gradle.kts
  package.json
  pnpm-workspace.yaml
  .env.example
  .editorconfig
```

根目录关键文件：

- `build.gradle.kts`：Java / Kotlin 多模块构建入口。
- `settings.gradle.kts`：Gradle 模块声明。
- `package.json`：前端 workspace 和常用脚本入口。
- `pnpm-workspace.yaml`：前端 workspace 范围。
- `Makefile`：本地常用命令快捷入口。
- `docker-compose.dev.yml`：本地开发基础设施。
- `docker-compose.yml`：集成/部署环境 Compose 骨架。
- `.env.example`：环境变量示例。
- `.editorconfig`、`.prettierrc`：格式统一配置。

## 3. apps

### 3.1 apps/web

React + TypeScript 前端应用。

主要包含：

- 工作流画布
- 节点配置面板
- 运行态页面
- 智能体模板库
- Skills / MCP 资产管理
- 权限与审计页面

### 3.2 apps/api

Java / Kotlin + Spring Boot 后端 API。

模块边界：

- `auth`：认证
- `permission`：权限
- `audit`：审计
- `workflow`：工作流定义与执行
- `agent`：智能体资产与运行
- `skill`：Skills 资产
- `mcp`：MCP 网关
- `delivery`：交付
- `knowledge`：知识库
- `modelprovider`：模型供应商
- `config`：系统配置
- `shared`：共享基础能力

## 4. packages

### 4.1 shared-contract

存放 OpenAPI、JSON Schema、事件契约。

前后端不通过复制类型来对齐，而是通过契约生成和校验保持一致。

### 4.2 ui

前端通用 UI 组件包。

只放跨页面、跨模块复用的基础组件，不放业务逻辑。

## 5. workers

### 5.1 document-worker

文档解析、Word / PDF 生成、模板渲染等长耗时任务。

### 5.2 ai-worker

复杂模型调用、批处理、Python 生态工具调用等任务。第一阶段可以保持占位。

## 6. deploy

部署相关配置。

- `docker`：各服务 Dockerfile。
- `nginx`：前端静态资源与 API 代理配置。
- `local`：本地开发环境初始化脚本。
- `k8s`：Kubernetes 部署占位，后续可扩展 Helm Chart 或原生 YAML。

## 7. 本地开发基础设施

`docker-compose.dev.yml` 提供：

- PostgreSQL
- Redis
- RabbitMQ
- MinIO
- Mailpit

这些服务覆盖数据库、缓存、队列、对象存储和邮件调试。

## 8. 构建与命令入口

当前工程同时包含前端和后端，因此命令入口分为三类：

- `pnpm`：前端应用和 UI 包。
- `gradle`：后端 API 与 Java Worker。
- `make`：常用本地命令快捷入口。

常用命令：

```bash
make dev-infra
make down-infra
pnpm dev:web
```

当前没有提交依赖锁文件，后续第一次安装依赖后应提交 `pnpm-lock.yaml`。
