# 技术栈建议

## 1. 总体结论

Agentum 更看重长期维护、企业级稳定和后期少改动，因此推荐：

```text
前端：React + TypeScript
后端：Java 21 / Kotlin + Spring Boot
数据库：PostgreSQL
缓存：Redis
队列：RabbitMQ / Kafka
文件：S3 兼容对象存储
AI Worker：Java 优先，复杂文档解析可引入 Python Worker
构建工具：Gradle Kotlin DSL
```

## 2. 为什么后端推荐 Java / Kotlin

Agentum 后端会长期承载以下复杂能力：

- 权限控制
- 审计日志
- 工作流状态机
- 发布版本管理
- 外部系统集成
- 事务一致性
- 长任务调度
- 生产环境稳定性

这些能力更偏企业系统，而不是简单 API 服务。Java / Kotlin 配合 Spring Boot 在团队维护、工程结构、监控、事务、权限和企业集成上更稳。

## 3. TypeScript 在项目中的位置

TypeScript 仍然非常重要，但主要用于前端和共享协议。

适合 TypeScript 的部分：

- 工作流画布
- 节点配置面板
- 动态表单
- 变量选择器
- 资产管理页面
- 前端状态管理
- OpenAPI 生成的接口类型

不建议第一版将核心后端完全押在 TypeScript 上，除非团队后端主力非常熟悉 Node.js 企业级工程。

## 4. React 与 Vue3 对比

### 4.1 React 优势

- 复杂画布生态更成熟，React Flow 很适合工作流编排场景。
- 适合动态组件、节点插件、配置面板插件等复杂扩展。
- AI SaaS、低代码、流程编排类产品参考更多。
- 与 shadcn/ui、Radix UI、TanStack Query、Zustand 等生态组合顺畅。

### 4.2 React 劣势

- 自由度高，需要项目规范兜住。
- Hooks、性能优化、状态边界对团队要求更高。
- 目录结构、组件分层、表单封装需要前期约定。

### 4.3 Vue3 优势

- 上手快，模板语法直观。
- 中后台表单、列表、权限页开发效率高。
- 配合 Element Plus、Naive UI、Ant Design Vue 可以快速交付。
- 如果团队熟悉 Vue，维护成本会低。

### 4.4 Vue3 劣势

- 画布和复杂编排生态通常不如 React 成熟。
- 节点插件化、动态配置面板、复杂可视化扩展资料相对少。
- 对 Agentum 这种“画布 + 智能体装配”产品，可能需要更多自研封装。

### 4.5 推荐选择

如果没有强团队偏好，Agentum 推荐 React。

原因：

```text
Agentum 的核心不是普通后台，而是工作流画布、动态节点配置、智能体装配和运行态可视化。
```

React 在这类复杂交互产品上更有优势。

## 5. 推荐前端技术栈

- React
- TypeScript
- Vite
- React Flow
- Tailwind CSS
- Radix UI / shadcn/ui
- lucide-react
- TanStack Query
- Zustand
- React Hook Form
- Zod
- OpenAPI TypeScript Client

## 6. 推荐后端技术栈

- Java 21 或 Kotlin
- Spring Boot
- Spring Security
- Spring Data JPA / MyBatis / jOOQ
- PostgreSQL
- Flyway / Liquibase
- Redis
- RabbitMQ 或 Kafka
- OpenAPI / Swagger
- MinIO 或 S3 兼容对象存储
- Micrometer + Prometheus + Grafana
- OpenTelemetry

## 7. 构建工具选择

当前工程采用 Gradle Kotlin DSL：

- `build.gradle.kts`
- `settings.gradle.kts`

这里的 Kotlin 不是要求业务代码必须使用 Kotlin，而是指 Gradle 配置文件使用 Kotlin DSL。

选择原因：

- 比 Maven XML 更适合多模块工程和后续任务编排。
- 比普通 Gradle Groovy DSL 类型更明确，IDE 补全和重构体验更好。
- 后续增加契约生成、前端构建联动、Worker 构建、发布任务时扩展性更好。
- 适合 Agentum 这种会逐步扩展为平台型 monorepo 的项目。

可以接受的替代方案：

- 如果团队是传统 Java 团队，且已有 Maven 标准，可以切换为 Maven 多模块。
- 如果团队已有 Gradle Groovy 经验，也可以使用普通 `build.gradle`。

当前推荐保持 Gradle Kotlin DSL，除非团队维护习惯明确偏向 Maven。

## 8. 推荐基础设施

开发环境：

- Docker Compose
- PostgreSQL
- Redis
- MinIO
- RabbitMQ
- Mailpit

生产环境：

- Kubernetes 或传统虚拟机部署均可。
- 日志集中采集。
- 指标监控。
- 链路追踪。
- 数据库定期备份。
- 对象存储生命周期管理。
