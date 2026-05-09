# db

数据库迁移脚本放在 `migration` 目录中，当前使用 Flyway 管理。

要求：

- 数据库结构变更必须新增迁移脚本并随代码提交。
- 迁移文件命名使用 `VyyyyMMddHHmm__description.sql`。
- 关键表和字段必须写中文注释，便于后续排查和交接。

## 本地开发身份数据

`V202605080001__extend_identity_and_seed_development_data.sql` 当前包含本地演示账号，统一初始密码为 `agentum123`：

| 用户名 | 入口 | 租户 |
| --- | --- | --- |
| `admin` | 系统管理 | 不绑定租户 |
| `operator` | 业务用户 | 云程科技 |
| `designer` | 业务用户 | 云程科技 |
| `tenantadmin` | 租户管理 | 云程科技 |

这批数据只用于第一阶段跑通登录、租户上下文和角色入口，生产初始化后续应改为管理员创建或环境专用 seed。
