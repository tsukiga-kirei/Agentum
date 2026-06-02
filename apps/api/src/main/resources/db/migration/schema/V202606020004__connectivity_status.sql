-- 系统管理能力与模型供应商的连通性探测结果落库，避免刷新后丢失在线/离线状态。

ALTER TABLE system_capabilities
    ADD COLUMN connectivity_status VARCHAR(30) NOT NULL DEFAULT 'offline',
    ADD COLUMN connectivity_checked_at TIMESTAMPTZ;

ALTER TABLE model_providers
    ADD COLUMN connectivity_status VARCHAR(30) NOT NULL DEFAULT 'offline',
    ADD COLUMN connectivity_checked_at TIMESTAMPTZ;

COMMENT ON COLUMN system_capabilities.connectivity_status IS '连通性状态：online 在线、offline 离线；配置变更后重置为 offline';
COMMENT ON COLUMN system_capabilities.connectivity_checked_at IS '最近一次连通性测试时间';
COMMENT ON COLUMN model_providers.connectivity_status IS '连通性状态：online 在线、offline 离线；配置变更后重置为 offline';
COMMENT ON COLUMN model_providers.connectivity_checked_at IS '最近一次连通性测试时间';
