-- 多交付进入“每个交付项独立选择交付方式”的口径。
-- 已执行过 V202607030001 的环境不能修改历史迁移，因此在后续脚本补齐 item.config.deliveryMode。
UPDATE workflow_node_definitions
SET config = config
    || jsonb_build_object('deliveryConfigMode', COALESCE(NULLIF(config ->> 'deliveryConfigMode', ''), 'single'))
    || jsonb_build_object(
        'deliveryItems',
        COALESCE((
            SELECT jsonb_agg(
                item || jsonb_build_object(
                    'config',
                    COALESCE(item -> 'config', '{}'::jsonb)
                    || jsonb_build_object(
                        'deliveryMode',
                        COALESCE(
                            NULLIF(item -> 'config' ->> 'deliveryMode', ''),
                            CASE
                                WHEN item -> 'config' ->> 'deliveryType' = 'direct'
                                    OR item -> 'config' ->> 'deliveryCapabilityId' IN ('none', 'custom')
                                    THEN 'direct'
                                WHEN NULLIF(item -> 'config' ->> 'deliveryCapabilityId', '') IS NOT NULL
                                    THEN 'capability'
                                WHEN config ->> 'deliveryMode' IN ('direct', 'capability')
                                    THEN config ->> 'deliveryMode'
                                ELSE 'direct'
                            END
                        )
                    )
                )
                ORDER BY item_order
            )
            FROM jsonb_array_elements(config -> 'deliveryItems') WITH ORDINALITY AS items(item, item_order)
        ), '[]'::jsonb)
    )
WHERE node_type = 'delivery'
  AND jsonb_typeof(config -> 'deliveryItems') = 'array';

COMMENT ON COLUMN workflow_node_definitions.config IS '节点配置 JSON。交付节点通过 deliveryConfigMode、deliveryExecutionPolicy 和 deliveryItems 编排单/多交付项；多交付时每个 item.config.deliveryMode 独立决定直接交付或能力交付。';
