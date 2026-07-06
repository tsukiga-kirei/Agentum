-- 本地演示数据：定时任务是业务工作台附加能力，演示账号需已具备 workbench 页签后再补充分配。
INSERT INTO page_grants (id, tenant_id, grant_group_id, grant_group_name, page_key, principal_type, principal_id)
VALUES
    ('00000000-0000-0000-0000-000000000714', '00000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000701', '演示业务页签', 'workbench_schedules', 'user', '00000000-0000-0000-0000-000000000002'),
    ('00000000-0000-0000-0000-000000000724', '00000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000702', '演示设计页签', 'workbench_schedules', 'user', '00000000-0000-0000-0000-000000000003')
ON CONFLICT (tenant_id, principal_type, principal_id, page_key) DO NOTHING;
