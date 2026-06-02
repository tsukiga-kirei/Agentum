# 产品运行时 Skills

本目录存放 Agentum 产品运行时 Skill 的源码材料。

每个 Skill 建议独立目录，至少包含：

```text
<skill-key>/
  SKILL.md       标准 Skill 正文，含 YAML frontmatter（name、description）
  skill.yaml     Agentum 发布元数据：版本、输入输出、风险等级
  examples/      输入、输出和业务样例
  tests/         Prompt 约束、输出 Schema 和回归样例
```

系统管理登记 Skill 时只需填写 `SKILL.md` 路径；`skill.yaml` 固定与 `SKILL.md` 同目录，连通性测试会自动读取两者。

发布链路：

```text
本目录开发和测试
  -> 能力管理员发布 Skill 版本
  -> 系统管理员或租户管理员授权
  -> 智能体模板或节点引用
  -> 运行时写入 Skill 快照和审计记录
```

`.codex/skills/` 只用于开发 Agentum 时辅助 Codex 工作，不进入这条发布链路。
