-- 多交付触发规则新增输入字段等于固定值、单智能体有输出内容两类。
-- 已运行环境不能修改 V202607030002，因此只在后续迁移中更新配置语义说明。
COMMENT ON COLUMN workflow_node_definitions.config IS '节点配置 JSON。交付节点通过 deliveryConfigMode、deliveryExecutionPolicy 和 deliveryItems 编排单/多交付项；多交付时每个 item.config.deliveryMode 独立决定直接交付或能力交付，item.triggerRule 支持始终触发、命中集群子智能体、输入字段等于固定值和单智能体有输出内容。';
