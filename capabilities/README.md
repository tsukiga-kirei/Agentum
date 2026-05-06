# 能力源码目录

`capabilities/` 存放 Agentum 产品运行时可版本化的能力源码和自研连接器。

这里不是权限台账，也不是数据库资产表的替代品。源码目录解决“能力如何开发、测试、发布和部署”；能力资产、系统管理和权限管理解决“能力如何登记、授权、启停、调用和审计”。

推荐分层：

```text
capabilities/
  skills/            产品运行时 Skill 定义、说明、样例和测试
  mcp-servers/       自研 MCP Server 源码、manifest、测试和部署说明
  prompt-templates/  提示词模板源码
  delivery/          交付适配器、文档模板和本地验证材料
```

不要把 `.codex/skills/` 里的开发辅助技能直接当作产品运行时 Skill 发布。两者服务对象不同：前者辅助本仓库开发，后者会被 Agentum 智能体运行时引用。
