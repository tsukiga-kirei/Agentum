-- 租户自建能力共享明细：发布与共享解耦，shared 能力通过本表指定可见同事。
CREATE TABLE tenant_asset_shares (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    asset_id UUID NOT NULL REFERENCES tenant_asset_capabilities (id) ON DELETE CASCADE,
    grantee_user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_by UUID REFERENCES users (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_tenant_asset_shares_asset_user
    ON tenant_asset_shares (asset_id, grantee_user_id);
CREATE INDEX idx_tenant_asset_shares_grantee
    ON tenant_asset_shares (tenant_id, grantee_user_id, created_at DESC);

COMMENT ON TABLE tenant_asset_shares IS '租户自建能力共享明细：仅 published 且 visibility=shared 的能力按指定用户开放';
COMMENT ON COLUMN tenant_asset_capabilities.visibility IS '可见范围：private 仅创建者使用，shared 通过 tenant_asset_shares 共享给指定用户';

-- 历史数据把发布时自动写入的 tenant 可见范围回退为 private，共享需由创建人重新配置。
UPDATE tenant_asset_capabilities
SET visibility = 'private'
WHERE visibility = 'tenant';
