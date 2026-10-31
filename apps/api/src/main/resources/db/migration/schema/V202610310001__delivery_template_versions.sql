CREATE TABLE delivery_template_versions (
    id UUID PRIMARY KEY,
    template_id UUID NOT NULL,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    name VARCHAR(160) NOT NULL,
    file_name VARCHAR(200) NOT NULL,
    kind VARCHAR(10) NOT NULL CHECK (kind IN ('word', 'excel')),
    version INTEGER NOT NULL CHECK (version > 0),
    status VARCHAR(20) NOT NULL CHECK (status IN ('draft', 'published')),
    read_scope VARCHAR(20) NOT NULL CHECK (read_scope IN ('self', 'all')),
    created_by UUID NOT NULL REFERENCES users(id),
    updated_at TIMESTAMPTZ NOT NULL,
    spec JSONB NOT NULL,
    file_bytes BYTEA NOT NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (template_id, version)
);
CREATE INDEX idx_delivery_template_tenant_updated ON delivery_template_versions(tenant_id, updated_at DESC);
COMMENT ON TABLE delivery_template_versions IS '新增文件模板及不可变发布版本，原有交付配置不迁移';
COMMENT ON COLUMN delivery_template_versions.id IS '模板版本标识，流程固定引用';
COMMENT ON COLUMN delivery_template_versions.template_id IS '同一模板的版本分组';
COMMENT ON COLUMN delivery_template_versions.tenant_id IS '租户隔离边界';
COMMENT ON COLUMN delivery_template_versions.name IS '模板名称';
COMMENT ON COLUMN delivery_template_versions.file_name IS '上传原件文件名';
COMMENT ON COLUMN delivery_template_versions.kind IS '模板文件类型 word/excel';
COMMENT ON COLUMN delivery_template_versions.version IS '模板版本序号';
COMMENT ON COLUMN delivery_template_versions.status IS '草稿可编辑，发布后不可修改';
COMMENT ON COLUMN delivery_template_versions.read_scope IS '仅创建者或租户内共享';
COMMENT ON COLUMN delivery_template_versions.created_by IS '模板创建者，独占编辑权限';
COMMENT ON COLUMN delivery_template_versions.updated_at IS '最后更新时间';
COMMENT ON COLUMN delivery_template_versions.spec IS '字段契约、位置样式及 Sheet 默认样式';
COMMENT ON COLUMN delivery_template_versions.file_bytes IS '版本原件，不受运行附件过期策略影响';
COMMENT ON COLUMN delivery_template_versions.lock_version IS '并发编辑乐观锁版本';
COMMENT ON INDEX idx_delivery_template_tenant_updated IS '租户模板列表按更新时间分页';
