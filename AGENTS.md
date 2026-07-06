# Agentum AI 开发必读规范

本文件是 AI 代理进入本仓库后的优先入口。开始任何代码、文档、配置或测试修改前，必须先阅读本文件，并按任务范围继续阅读对应规范。

## 1. 每次开发前必须阅读

所有任务都必须先阅读：

- `README.md`
- `docs/development-standards.md`
- `docs/system-overview.md`
- `docs/architecture.md`
- `docs/project-structure.md`
- `docs/progress/README.md`

涉及前端、页面、组件、交互或样式时，还必须阅读：

- `docs/system-overview.md`

涉及工作流、节点、变量、运行态或 AI 交互时，还必须阅读：

- `docs/system-overview.md`
- `docs/architecture.md`

涉及权限、审计、角色、凭证或敏感操作时，还必须阅读：

- `docs/system-overview.md`
- `docs/architecture.md`

涉及 Skills、MCP、智能体模板或能力资产时，还必须阅读：

- `docs/system-overview.md`
- `docs/architecture.md`

涉及架构、模块边界、技术选型或新增服务时，还必须阅读：

- `docs/architecture.md`

## 2. 当前实现边界与常用路径

当前项目处于阶段一：框架与基础治理。已有实现包括认证与角色切换、租户公开列表、租户组织管理、租户内页签和能力分配、系统管理、能力资产草稿发布、工作流草稿、变量声明、发布校验和不可变版本快照。工作流运行态、智能体真实执行、MCP 网关、模型调用、交付闭环和完整审计链路仍在建设中，修改时不要把这些能力误认为已经完整落地。

常用代码路径：

- 前端入口、路由和工作台：`apps/web/src/App.tsx`、`apps/web/src/surfaces/`、`apps/web/src/components/`
- 前端 API、状态和类型：`apps/web/src/services/apiClient.ts`、`apps/web/src/stores/`、`apps/web/src/types/`
- 认证与角色上下文：`apps/api/src/main/java/com/agentum/auth/`
- 租户、组织和权限：`apps/api/src/main/java/com/agentum/tenant/`、`apps/api/src/main/java/com/agentum/organization/`、`apps/api/src/main/java/com/agentum/permission/`
- 系统管理：`apps/api/src/main/java/com/agentum/system/`
- 能力资产：`apps/api/src/main/java/com/agentum/asset/`
- 工作流设计：`apps/api/src/main/java/com/agentum/workflow/`、`apps/web/src/surfaces/designer/`
- 统一响应、错误和分页：`apps/api/src/main/java/com/agentum/shared/`
- 数据库迁移：`apps/api/src/main/resources/db/migration/schema/`；本地演示数据放 `apps/api/src/main/resources/db/migration/devdata/`
- 共享契约：`packages/shared-contract/`
- 产品运行时能力源码：`capabilities/`

常用验证命令：

- 前端：`pnpm lint:web`、`pnpm build:web`
- 后端：`./gradlew test`，必要时按模块或测试类缩小范围
- 文档和空白检查：`git diff --check`
- 本地基础设施：`make dev-infra`、`make down-infra`

## 3. 开发约束

- 遵守 `docs/development-standards.md` 中的命名、注释、接口、错误码、测试和格式化规范。
- 注释、错误 message、产品文案和文档默认使用中文。
- 新增复杂逻辑必须写中文注释，说明业务原因、临时模拟数据来源和后续替换方向；不要只复述代码表面行为。
- 涉及权限、租户上下文、认证、成员关系、状态流转、外部调用和失败分支的代码必须有适度中文注释，解释业务约束和安全边界。
- 新增后端服务、过滤器、异常处理、权限判断和写入动作必须补结构化日志；日志默认使用中文，必须带 requestId、tenantId、userId 等可追踪上下文，禁止输出密码、Token、密钥、凭证明文和供应商敏感原始响应。
- 前端调用后端 API、会话恢复、权限动作和表单提交失败时，必须保留用户可见错误；必要时可使用 `console.warn` / `console.error` 输出脱敏诊断信息，禁止输出密码、Token、密钥和完整敏感响应。
- 核心逻辑必须有测试；涉及权限、状态机、变量解析、MCP 调用、模型输出解析、审计、重试和补偿时尤其必须补测试。
- API 返回结构、错误结构和错误码前缀必须保持统一。
- 前端应保持企业工作台风格，避免营销页式布局和无意义装饰。
- 画布、节点卡片、右侧配置面板、运行态界面必须优先保证信息可读、状态稳定和长期可维护。

## 4. 修改原则

- 先理解现有目录和局部实现，再修改。
- 优先复用已有模式、类型、组件、工具函数和测试风格。
- 修改范围尽量小，避免顺手重构无关代码。
- 不覆盖或回滚他人未说明的改动。
- 当前产品仍处于未上线阶段，开发期不为了早期本地草稿、演示数据或旧字段长期保留兼容逻辑；当旧设计与最优产品心智冲突时，应优先删除旧路径、修正种子数据和测试，以清晰、可维护的新模型为准。
- 新增文档时同步维护 `README.md` 中的入口索引；阶段进度类文档同步维护 `docs/progress/README.md`；目录结构变化时同步维护 `docs/project-structure.md`。

## 5. 验证要求

完成修改后，根据影响范围运行对应验证：

- 前端：类型检查、lint、相关单元测试或页面验证。
- 后端：相关 Gradle 测试、格式化或静态检查。
- 文档：检查链接、标题层级和术语一致性。
- 配置或部署：检查示例命令、环境变量和本地启动路径是否仍然成立。

如果无法运行验证，必须在最终说明里明确原因和剩余风险。
