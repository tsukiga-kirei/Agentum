# Agentum 测试 MCP

这是一个用于系统管理页连通性测试的 Java / Spring AI MCP Server 示例。

## 启动

```bash
./gradlew -p capabilities/mcp-servers/agentum-test-mcp bootRun
```

默认地址：

- MCP SSE：`http://localhost:18080/sse`

系统管理中登记 MCP 时填写：

- 能力类型：`MCP`
- SSE 地址：`http://localhost:18080/sse`

测试连通性时会按 MCP 标准协议执行 `initialize` 与 `tools/list`，并在结果弹窗展示工具清单。

## 工具

- `agentum.health_check`：返回服务状态。
- `agentum.echo_context`：回显业务上下文，用于验证参数传入和返回结构。

## 说明

该示例使用 Spring AI MCP Server WebMVC SSE starter。MCP Server 只负责暴露工具能力，不处理租户、角色、凭证和审计；这些能力后续由 Agentum API 的 MCP 网关统一承担。
