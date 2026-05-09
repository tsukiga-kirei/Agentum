# shared-contract

这里存放跨前后端共享的协议、OpenAPI、JSON Schema 和事件契约。

原则：

- 前端类型尽量从 OpenAPI 或 JSON Schema 生成。
- 后端接口变更必须先更新契约。
- 工作流节点、变量、智能体、MCP、提示词模板、交付能力和单租户能力配置的核心协议必须版本化。
