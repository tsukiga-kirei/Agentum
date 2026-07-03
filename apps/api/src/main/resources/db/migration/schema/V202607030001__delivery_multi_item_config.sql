-- 交付节点新增“能力交付下的单/多交付配置”口径。
-- 开发期不保留旧设计分支：已有交付节点统一补 deliveryConfigMode，直接交付清空 deliveryItems。
UPDATE workflow_node_definitions
SET config = CASE
    WHEN config ->> 'deliveryMode' = 'direct' OR config ->> 'deliveryType' = 'direct' THEN
        config
            || jsonb_build_object('deliveryConfigMode', 'single')
            || jsonb_build_object('deliveryItems', '[]'::jsonb)
    ELSE
        config
            || jsonb_build_object('deliveryMode', 'capability')
            || jsonb_build_object('deliveryConfigMode', COALESCE(NULLIF(config ->> 'deliveryConfigMode', ''), 'single'))
            || jsonb_build_object('deliveryExecutionPolicy', COALESCE(NULLIF(config ->> 'deliveryExecutionPolicy', ''), 'all'))
END
WHERE node_type = 'delivery';

COMMENT ON COLUMN workflow_node_definitions.config IS '节点配置 JSON。交付节点保留 deliveryMode 作为交付方式；能力交付下通过 deliveryConfigMode、deliveryExecutionPolicy 和 deliveryItems 编排单/多交付项。';
