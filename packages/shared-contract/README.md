# shared-contract

跨前后端、运行态与 Worker 共享的协议目录。这里是**字段与接口的单一事实来源**，不是可执行代码。

## 放什么

| 子目录 | 内容 | 典型用途 |
| --- | --- | --- |
| `openapi/` | REST API 描述（`agentum.openapi.yaml`） | 登录、租户、工作流、工作台、审计等 HTTP 接口的路径、请求/响应、错误码、分页 |
| `schemas/` | 领域对象 JSON Schema | 工作流节点、变量、智能体、MCP、提示词模板、交付能力、租户能力授权等**可版本化配置** |
| `events/` | 异步事件 JSON Schema | 节点执行命令、运行态进度/SSE 事件，供 MQ 与流式推送对齐 |

## 解决什么问题

1. **前后端对齐**：避免 `apps/api` 改了 DTO、`apps/web` 手写类型未跟进。
2. **设计态与运行态对齐**：流程发布快照、导入导出、校验器都引用同一套节点/变量 Schema。
3. **运行时与 Worker 对齐**：执行命令和进度事件有固定结构，便于 RabbitMQ / Redis Stream 消费与回放。
4. **可生成代码**：后续用 OpenAPI Generator 或 JSON Schema → TypeScript，减少 `apps/web/src/types/` 手写维护。

## 变更原则

- **先改契约，再改后端与前端实现。**
- 涉及工作流节点、变量、智能体输出、权限动作、分页响应时，必须补测试。
- 契约变更同步更新相关 `docs/` 说明。

## 与 capabilities/ 的区别

| | `packages/shared-contract` | `capabilities/` |
| --- | --- | --- |
| 内容 | 协议与数据结构 | Skill / MCP / 交付适配器的可执行源码 |
| 消费者 | 前端、API、Worker、校验器 | 运行时网关、部署流水线 |
| 变更频率 | 随 API 与领域模型演进 | 随能力版本发布 |
