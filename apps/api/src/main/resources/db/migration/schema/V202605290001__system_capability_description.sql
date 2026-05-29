-- 系统能力补充业务说明字段，供系统管理与能力资产页展示分配后的能力用途。
ALTER TABLE system_capabilities
    ADD COLUMN description TEXT NOT NULL DEFAULT '';

COMMENT ON COLUMN system_capabilities.description IS '能力业务说明，描述用途、输入约束和后续接入方向';
