# db

数据库迁移脚本放在 `migration` 目录中，当前使用 Flyway 管理。

要求：

- 数据库结构变更必须新增迁移脚本并随代码提交。
- 迁移文件命名使用 `VyyyyMMddHHmm__description.sql`。
- 关键表和字段必须写中文注释，便于后续排查和交接。
