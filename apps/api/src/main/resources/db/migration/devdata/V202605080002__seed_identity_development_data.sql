-- 本地开发身份种子：用于跑通租户选择、登录入口和活跃角色上下文。
-- 这些账号不属于生产初始化数据，后续生产环境应通过系统管理或环境专用 seed 创建。
INSERT INTO tenants (id, name, code, status, contact_name, contact_email)
VALUES
    ('00000000-0000-0000-0000-000000000101', '云程科技', 'YUNCHENG', 'active', '云程管理员', 'admin@yuncheng.example'),
    ('00000000-0000-0000-0000-000000000102', '北辰制造', 'NORTHSTAR', 'active', '北辰管理员', 'admin@northstar.example'),
    ('00000000-0000-0000-0000-000000000103', '明衡法务', 'MINGHENG', 'active', '明衡管理员', 'admin@mingheng.example');

INSERT INTO departments (id, tenant_id, name, code, sort_order, status)
VALUES
    ('00000000-0000-0000-0000-000000000301', '00000000-0000-0000-0000-000000000101', '默认部门', 'default', 0, 'active');

INSERT INTO roles (id, tenant_id, code, name, scope, description, built_in, status)
VALUES
    ('00000000-0000-0000-0000-000000000201', NULL, 'system_admin', '系统管理员', 'system', '管理全局租户、模型、底层能力和系统策略', true, 'active'),
    ('00000000-0000-0000-0000-000000000211', '00000000-0000-0000-0000-000000000101', 'executor', '执行人', 'business', '发起流程、处理输入和查看交付物', true, 'active'),
    ('00000000-0000-0000-0000-000000000212', '00000000-0000-0000-0000-000000000101', 'workflow_designer', '流程设计者', 'business', '创建和维护工作流草稿、节点和变量', true, 'active'),
    ('00000000-0000-0000-0000-000000000213', '00000000-0000-0000-0000-000000000101', 'tenant_admin', '租户管理员', 'tenant', '管理租户成员、角色权限、资源授权和能力分配', true, 'active');

INSERT INTO users (id, username, password_hash, display_name, email, status)
VALUES
    ('00000000-0000-0000-0000-000000000001', 'admin', '$2y$10$OjBJvVTuDJCyItNzG/HeeOh..7jSMpS4ySjfW5Ga1gNj2CnxLk/vK', '系统管理员', 'admin@agentum.dev', 'active'),
    ('00000000-0000-0000-0000-000000000002', 'operator', '$2y$10$OjBJvVTuDJCyItNzG/HeeOh..7jSMpS4ySjfW5Ga1gNj2CnxLk/vK', '业务用户', 'operator@agentum.dev', 'active'),
    ('00000000-0000-0000-0000-000000000003', 'designer', '$2y$10$OjBJvVTuDJCyItNzG/HeeOh..7jSMpS4ySjfW5Ga1gNj2CnxLk/vK', '流程设计者', 'designer@agentum.dev', 'active'),
    ('00000000-0000-0000-0000-000000000004', 'tenantadmin', '$2y$10$OjBJvVTuDJCyItNzG/HeeOh..7jSMpS4ySjfW5Ga1gNj2CnxLk/vK', '租户管理员', 'tenantadmin@agentum.dev', 'active');

INSERT INTO system_user_roles (id, user_id, role_id)
VALUES
    ('00000000-0000-0000-0000-000000000401', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000201');

INSERT INTO user_memberships (id, tenant_id, user_id, department_id, space_code, is_default, status)
VALUES
    ('00000000-0000-0000-0000-000000000501', '00000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000301', '默认空间', true, 'active'),
    ('00000000-0000-0000-0000-000000000502', '00000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000301', '默认空间', true, 'active'),
    ('00000000-0000-0000-0000-000000000503', '00000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000301', '默认空间', true, 'active');

INSERT INTO user_membership_roles (id, membership_id, role_id, status)
VALUES
    ('00000000-0000-0000-0000-000000000601', '00000000-0000-0000-0000-000000000501', '00000000-0000-0000-0000-000000000211', 'active'),
    ('00000000-0000-0000-0000-000000000602', '00000000-0000-0000-0000-000000000502', '00000000-0000-0000-0000-000000000212', 'active'),
    ('00000000-0000-0000-0000-000000000603', '00000000-0000-0000-0000-000000000503', '00000000-0000-0000-0000-000000000213', 'active');
