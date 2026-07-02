-- 将智能体集群执行方式从历史技术枚举迁移为当前产品语义枚举。
-- 当前产品仍处于未上线阶段，直接清洗草稿、发布快照和运行快照，避免长期保留 parallel/sequential 兼容分支。

UPDATE workflow_node_definitions
SET config = jsonb_set(config, '{executionMode}', to_jsonb('collaborative'::text), false)
WHERE node_type = 'parallel_group'
  AND config ->> 'executionMode' = 'parallel';

UPDATE workflow_node_definitions
SET config = jsonb_set(config, '{executionMode}', to_jsonb('relay'::text), false)
WHERE node_type = 'parallel_group'
  AND config ->> 'executionMode' = 'sequential';

UPDATE workflow_node_runs
SET config_snapshot = jsonb_set(config_snapshot, '{executionMode}', to_jsonb('collaborative'::text), false)
WHERE node_type = 'parallel_group'
  AND config_snapshot ->> 'executionMode' = 'parallel';

UPDATE workflow_node_runs
SET config_snapshot = jsonb_set(config_snapshot, '{executionMode}', to_jsonb('relay'::text), false)
WHERE node_type = 'parallel_group'
  AND config_snapshot ->> 'executionMode' = 'sequential';

UPDATE workflow_versions version_row
SET definition_snapshot = jsonb_set(
    definition_snapshot,
    '{nodes}',
    (
        SELECT jsonb_agg(
            CASE
                WHEN node_item.node ->> 'nodeType' = 'parallel_group'
                    AND node_item.node #>> '{config,executionMode}' = 'parallel'
                    THEN jsonb_set(node_item.node, '{config,executionMode}', to_jsonb('collaborative'::text), false)
                WHEN node_item.node ->> 'nodeType' = 'parallel_group'
                    AND node_item.node #>> '{config,executionMode}' = 'sequential'
                    THEN jsonb_set(node_item.node, '{config,executionMode}', to_jsonb('relay'::text), false)
                ELSE node_item.node
            END
            ORDER BY node_item.ordinality
        )
        FROM jsonb_array_elements(version_row.definition_snapshot -> 'nodes') WITH ORDINALITY AS node_item(node, ordinality)
    ),
    false
)
WHERE jsonb_typeof(definition_snapshot -> 'nodes') = 'array'
  AND EXISTS (
    SELECT 1
    FROM jsonb_array_elements(version_row.definition_snapshot -> 'nodes') AS node_item(node)
    WHERE node_item.node ->> 'nodeType' = 'parallel_group'
      AND node_item.node #>> '{config,executionMode}' IN ('parallel', 'sequential')
  );
