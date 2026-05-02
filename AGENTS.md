# Agentum AI 开发必读规范

本文件是 AI 代理进入本仓库后的优先入口。开始任何代码、文档、配置或测试修改前，必须先阅读本文件，并按任务范围继续阅读对应规范。

## 1. 每次开发前必须阅读

所有任务都必须先阅读：

- `README.md`
- `docs/README.md`
- `docs/development-plan.md`
- `docs/development-standards.md`
- `docs/project-structure.md`

涉及前端、页面、组件、交互或样式时，还必须阅读：

- `docs/frontend-guidelines.md`
- `docs/frontend-workflow-visualization.md`

涉及工作流、节点、变量、运行态或 AI 交互时，还必须阅读：

- `docs/workflow-engine.md`
- `docs/ai-interaction-runtime.md`

涉及权限、审计、角色、凭证或敏感操作时，还必须阅读：

- `docs/permission-model.md`

涉及 Skills、MCP、智能体模板或能力资产时，还必须阅读：

- `docs/skills-and-mcp.md`

涉及架构、模块边界、技术选型或新增服务时，还必须阅读：

- `docs/architecture.md`
- `docs/technology-stack.md`

## 2. 开发约束

- 遵守 `docs/development-standards.md` 中的命名、注释、接口、错误码、测试和格式化规范。
- 注释、错误 message、产品文案和文档默认使用中文。
- 核心逻辑必须有测试；涉及权限、状态机、变量解析、MCP 调用、模型输出解析、审计、重试和补偿时尤其必须补测试。
- API 返回结构、错误结构和错误码前缀必须保持统一。
- 前端应保持企业工作台风格，避免营销页式布局和无意义装饰。
- 画布、节点卡片、右侧配置面板、运行态界面必须优先保证信息可读、状态稳定和长期可维护。

## 3. 修改原则

- 先理解现有目录和局部实现，再修改。
- 优先复用已有模式、类型、组件、工具函数和测试风格。
- 修改范围尽量小，避免顺手重构无关代码。
- 不覆盖或回滚他人未说明的改动。
- 新增文档时同步维护 `README.md` 或 `docs/README.md` 中的入口索引。

## 4. 验证要求

完成修改后，根据影响范围运行对应验证：

- 前端：类型检查、lint、相关单元测试或页面验证。
- 后端：相关 Gradle 测试、格式化或静态检查。
- 文档：检查链接、标题层级和术语一致性。
- 配置或部署：检查示例命令、环境变量和本地启动路径是否仍然成立。

如果无法运行验证，必须在最终说明里明确原因和剩余风险。
