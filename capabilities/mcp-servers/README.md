# 自研 MCP Server

本目录存放 Agentum 自研 MCP Server 的源码。

每个 MCP Server 建议独立目录，并保持可独立启动、测试和部署：

```text
<server-key>/
  manifest.yaml  工具列表、参数 Schema、风险等级和凭证需求
  src/           Server 源码
  tests/         工具调用、参数校验、脱敏和错误场景测试
  README.md      本地启动、环境变量和部署说明
```

接入原则：

- MCP Server 只负责具体工具能力，不直接决定租户、角色和资源权限。
- 所有运行时调用必须经过 API 服务的 MCP 网关。
- MCP 网关负责鉴权、凭证注入、限流、参数脱敏、结果脱敏和审计写入。
- 高风险工具必须进入敏感动作审批或二次确认链路。
- Server 中不能硬编码租户密钥、用户凭证或生产地址。

推荐接入链路：

```text
本目录开发 MCP Server
  -> 本地 manifest 和工具调用测试
  -> 系统管理员登记为全局 MCP
  -> 授权给租户
  -> 租户管理员授权给角色、智能体或工作流
  -> 运行时由 MCP 网关统一调用
```
