-- 统一工作流系统变量命名：移除 year/month/day 别名，并让日期规则与运行时变量同名。
-- 当前项目尚未上线，直接迁移已有草稿、发布快照与定时任务配置，避免长期保留两套产品心智。
CREATE FUNCTION normalize_workflow_system_variables_v20260715(source text)
RETURNS text
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT regexp_replace(
        regexp_replace(
            regexp_replace(
                regexp_replace(
                    regexp_replace(
                        regexp_replace(
                            regexp_replace(
                                $1,
                                '\{\{\s*year\s*\}\}', '{{current_year}}', 'g'
                            ),
                            '\{\{\s*month\s*\}\}', '{{current_month_padded}}', 'g'
                        ),
                        '\{\{\s*day\s*\}\}', '{{current_day_padded}}', 'g'
                    ),
                    '("systemDefaultValue"\s*:\s*")current_month(")', '\1current_year_month\2', 'g'
                ),
                '("systemDefaultValue"\s*:\s*")previous_month(")', '\1previous_year_month\2', 'g'
            ),
            '("rule"\s*:\s*")current_month(")', '\1current_year_month\2', 'g'
        ),
        '("rule"\s*:\s*")previous_month(")', '\1previous_year_month\2', 'g'
    );
$$;

UPDATE workflow_node_definitions
SET config = normalize_workflow_system_variables_v20260715(config::text)::jsonb;

UPDATE workflow_versions
SET definition_snapshot = normalize_workflow_system_variables_v20260715(definition_snapshot::text)::jsonb;

UPDATE workflow_schedules
SET input_payload = normalize_workflow_system_variables_v20260715(input_payload::text)::jsonb;

UPDATE workflow_node_runs
SET config_snapshot = normalize_workflow_system_variables_v20260715(config_snapshot::text)::jsonb,
    input_snapshot = normalize_workflow_system_variables_v20260715(input_snapshot::text)::jsonb,
    output_snapshot = normalize_workflow_system_variables_v20260715(output_snapshot::text)::jsonb;

UPDATE workflow_runs
SET trigger_payload = normalize_workflow_system_variables_v20260715(trigger_payload::text)::jsonb;

UPDATE prompt_templates
SET content = normalize_workflow_system_variables_v20260715(content);

UPDATE system_capabilities
SET config = normalize_workflow_system_variables_v20260715(config::text)::jsonb;

UPDATE tenant_asset_capabilities
SET config = normalize_workflow_system_variables_v20260715(config::text)::jsonb;

UPDATE delivery_capabilities
SET config = normalize_workflow_system_variables_v20260715(config::text)::jsonb;

DROP FUNCTION normalize_workflow_system_variables_v20260715(text);
