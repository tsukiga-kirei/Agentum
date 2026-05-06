# 产品运行时 Skills

本目录存放 Agentum 产品运行时 Skill 的源码材料。

每个 Skill 建议独立目录：

```text
<skill-key>/
  SKILL.md       面向智能体运行时的能力说明、使用边界和输出要求
  skill.yaml     名称、版本、输入输出、风险等级、可用租户等发布元数据
  examples/      输入、输出和业务样例
  tests/         Prompt 约束、输出 Schema 和回归样例
```

发布链路：

```text
本目录开发和测试
  -> 能力管理员发布 Skill 版本
  -> 系统管理员或租户管理员授权
  -> 智能体模板或节点引用
  -> 运行时写入 Skill 快照和审计记录
```

`.codex/skills/` 只用于开发 Agentum 时辅助 Codex 工作，不进入这条发布链路。
