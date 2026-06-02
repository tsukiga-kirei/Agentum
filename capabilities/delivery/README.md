# 交付能力实现

本目录存放交付能力的适配器、模板和本地验证材料。

本目录只存放自定义交付适配器。系统内置交付能力（例如邮箱发送）由 Agentum API 原生实现，不放在本目录。

建议目录：

```text
<delivery-key>/
  manifest.yaml  统一协议声明：配置、输入、输出、风险等级和调用入口
  src/            适配器源码或脚本
  tests/          参数校验、失败重试和脱敏测试
  README.md    本地验证和部署说明
```

高风险交付能力必须经过权限校验、审批或二次确认，并写入交付记录和审计日志。

当前统一协议字段：

- `key`：能力实现标识。
- `runtime`：适配器运行方式，当前先支持 `http`。
- `entry.url`：适配器调用入口。
- `configSchema`：系统管理配置字段。
- `inputSchema`：交付节点输入结构。
- `outputSchema`：适配器返回结构。
- `riskLevel`：风险等级。

已提供示例：

- `custom-oa-delivery/`：自定义 OA 流程交付适配器协议示例。
