-- 多交付触发规则收敛为两类：始终触发、命中智能体集群中的某个子智能体。
-- 开发期上一版变量存在 / 变量等于 / 意图代码规则不再保留，统一回到始终触发，由设计者重新选择明确的集群子智能体。
UPDATE workflow_node_definitions
SET config = jsonb_set(
    config,
    '{deliveryItems}',
    COALESCE((
        SELECT jsonb_agg(
            CASE
                WHEN item -> 'triggerRule' ->> 'type' IN ('variable_exists', 'variable_equals', 'agent_output_exists', 'intent_code') THEN
                    jsonb_set(
                        item,
                        '{triggerRule}',
                        jsonb_build_object(
                            'type', 'always',
                            'clusterNodeId', '',
                            'agentId', '',
                            'variableName', ''
                        )
                    )
                ELSE item
            END
        )
        FROM jsonb_array_elements(config -> 'deliveryItems') AS item
    ), '[]'::jsonb)
)
WHERE node_type = 'delivery'
  AND jsonb_typeof(config -> 'deliveryItems') = 'array';
