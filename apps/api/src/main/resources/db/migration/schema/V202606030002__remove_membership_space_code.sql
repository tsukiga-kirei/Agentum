ALTER TABLE user_memberships
    DROP COLUMN IF EXISTS space_code;

COMMENT ON TABLE user_memberships IS '用户租户成员关系表，记录用户在租户和部门中的身份';
