  CREATE TABLE attachment_recognition_settings (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    recognition_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    recognition_engine VARCHAR(20) NOT NULL DEFAULT 'local',
    max_file_size_mb INTEGER NOT NULL DEFAULT 20,
    max_files_per_field INTEGER NOT NULL DEFAULT 5,
    max_extracted_chars INTEGER NOT NULL DEFAULT 200000,
    retention_policy VARCHAR(20) NOT NULL DEFAULT 'permanent',
    retention_days INTEGER NOT NULL DEFAULT 30,
    mineru_supported_extensions JSONB NOT NULL DEFAULT '["pdf","png","jpg","jpeg","bmp","gif","tiff","webp","docx","xlsx","txt"]'::jsonb,
    mineru_endpoint VARCHAR(800),
    encrypted_mineru_api_key TEXT,
    mineru_backend VARCHAR(80) NOT NULL DEFAULT 'pipeline',
    mineru_parse_method VARCHAR(20) NOT NULL DEFAULT 'ocr',
    mineru_language VARCHAR(40) NOT NULL DEFAULT 'ch',
    mineru_enable_formula BOOLEAN NOT NULL DEFAULT TRUE,
    mineru_enable_table BOOLEAN NOT NULL DEFAULT TRUE,
    mineru_connect_timeout_seconds INTEGER NOT NULL DEFAULT 10,
    mineru_read_timeout_seconds INTEGER NOT NULL DEFAULT 300,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_attachment_recognition_engine CHECK (recognition_engine IN ('local', 'mineru')),
    CONSTRAINT ck_attachment_mineru_parse_method CHECK (mineru_parse_method IN ('auto', 'txt', 'ocr')),
    CONSTRAINT ck_attachment_retention_policy CHECK (retention_policy IN ('permanent', 'days')),
    CONSTRAINT ck_attachment_setting_limits CHECK (
        max_file_size_mb BETWEEN 1 AND 200
        AND max_files_per_field BETWEEN 1 AND 20
        AND max_extracted_chars BETWEEN 1000 AND 2000000
        AND retention_days BETWEEN 1 AND 3650
        AND mineru_connect_timeout_seconds BETWEEN 1 AND 120
        AND mineru_read_timeout_seconds BETWEEN 10 AND 3600
    )
);

INSERT INTO attachment_recognition_settings (id) VALUES (1);

CREATE TABLE input_attachments (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    run_id UUID NOT NULL REFERENCES workflow_runs(id) ON DELETE CASCADE,
    node_run_id UUID NOT NULL REFERENCES workflow_node_runs(id) ON DELETE CASCADE,
    field_id VARCHAR(120) NOT NULL,
    variable_key VARCHAR(120) NOT NULL,
    uploaded_by UUID NOT NULL REFERENCES users(id),
    original_file_name VARCHAR(255) NOT NULL,
    extension VARCHAR(30) NOT NULL,
    content_type VARCHAR(160) NOT NULL,
    size_bytes BIGINT NOT NULL,
    content_sha256 VARCHAR(64) NOT NULL,
    storage_key VARCHAR(1000) NOT NULL,
    recognition_engine VARCHAR(20) NOT NULL,
    status VARCHAR(30) NOT NULL,
    error_code VARCHAR(100),
    error_message VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    CONSTRAINT ck_input_attachment_status CHECK (status IN ('queued', 'parsing', 'ready', 'failed', 'rejected')),
    CONSTRAINT ck_input_attachment_engine CHECK (recognition_engine IN ('none', 'local', 'mineru')),
    CONSTRAINT ck_input_attachment_size CHECK (size_bytes > 0)
);

CREATE INDEX idx_input_attachments_node_field ON input_attachments(tenant_id, run_id, node_run_id, field_id, created_at);
CREATE INDEX idx_input_attachments_expiry ON input_attachments(expires_at, status);

CREATE TABLE attachment_parse_results (
    id UUID PRIMARY KEY,
    attachment_id UUID NOT NULL UNIQUE REFERENCES input_attachments(id) ON DELETE CASCADE,
    parser_type VARCHAR(30) NOT NULL,
    parser_version VARCHAR(80) NOT NULL,
    parser_config_hash VARCHAR(64) NOT NULL,
    content_storage_key VARCHAR(1000),
    character_count INTEGER NOT NULL DEFAULT 0,
    truncated BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(30) NOT NULL,
    error_code VARCHAR(100),
    error_message VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_attachment_parse_status CHECK (status IN ('parsing', 'ready', 'failed'))
);

COMMENT ON TABLE attachment_recognition_settings IS '平台级附件识别设置；MinerU 密钥只保存加密值';
COMMENT ON TABLE input_attachments IS '工作流输入节点上传的原始附件及解析状态';
COMMENT ON TABLE attachment_parse_results IS '附件解析结果元数据；完整正文保存在对象存储';
COMMENT ON COLUMN attachment_recognition_settings.id IS '单例配置主键，固定为 1';
COMMENT ON COLUMN attachment_recognition_settings.recognition_enabled IS '是否启用附件正文识别；关闭后仍允许上传附件';
COMMENT ON COLUMN attachment_recognition_settings.recognition_engine IS '识别方式：local 简单识别，mineru 复杂识别';
COMMENT ON COLUMN attachment_recognition_settings.max_file_size_mb IS '平台允许的单个附件最大大小，单位 MB';
COMMENT ON COLUMN attachment_recognition_settings.max_files_per_field IS '单个输入字段允许上传的最大附件数量';
COMMENT ON COLUMN attachment_recognition_settings.max_extracted_chars IS '单个附件允许保留的最大解析字符数';
COMMENT ON COLUMN attachment_recognition_settings.retention_policy IS '附件默认保存策略：permanent 永久保存，days 按天保存';
COMMENT ON COLUMN attachment_recognition_settings.retention_days IS '按天保存时原附件与解析结果的保留天数；永久保存时不生效';
COMMENT ON COLUMN attachment_recognition_settings.mineru_supported_extensions IS '复杂识别允许发送给 MinerU 的自定义扩展名白名单';
COMMENT ON COLUMN attachment_recognition_settings.mineru_endpoint IS 'MinerU 服务根地址';
COMMENT ON COLUMN attachment_recognition_settings.encrypted_mineru_api_key IS 'MinerU API Key 加密密文，禁止回显和写入日志';
COMMENT ON COLUMN attachment_recognition_settings.mineru_backend IS 'MinerU backend 参数';
COMMENT ON COLUMN attachment_recognition_settings.mineru_parse_method IS 'MinerU 解析方式：auto、txt 或 ocr';
COMMENT ON COLUMN attachment_recognition_settings.mineru_language IS 'MinerU 文档解析语言';
COMMENT ON COLUMN attachment_recognition_settings.mineru_enable_formula IS 'MinerU 是否启用公式识别';
COMMENT ON COLUMN attachment_recognition_settings.mineru_enable_table IS 'MinerU 是否启用表格识别';
COMMENT ON COLUMN attachment_recognition_settings.mineru_connect_timeout_seconds IS '连接 MinerU 的超时秒数';
COMMENT ON COLUMN attachment_recognition_settings.mineru_read_timeout_seconds IS '等待 MinerU 解析响应的超时秒数';
COMMENT ON COLUMN attachment_recognition_settings.created_at IS '配置创建时间';
COMMENT ON COLUMN attachment_recognition_settings.updated_at IS '配置最后更新时间';
COMMENT ON COLUMN input_attachments.id IS '附件主键';
COMMENT ON COLUMN input_attachments.tenant_id IS '附件所属租户';
COMMENT ON COLUMN input_attachments.run_id IS '附件所属工作流运行';
COMMENT ON COLUMN input_attachments.node_run_id IS '附件所属输入节点运行';
COMMENT ON COLUMN input_attachments.field_id IS '发布快照中的输入字段标识';
COMMENT ON COLUMN input_attachments.variable_key IS '输入字段对应的工作流变量名';
COMMENT ON COLUMN input_attachments.uploaded_by IS '附件上传用户';
COMMENT ON COLUMN input_attachments.original_file_name IS '用户上传时的原始文件名';
COMMENT ON COLUMN input_attachments.extension IS '规范化扩展名，不含点号';
COMMENT ON COLUMN input_attachments.content_type IS '后端确认后的文件 MIME 类型';
COMMENT ON COLUMN input_attachments.size_bytes IS '附件字节大小';
COMMENT ON COLUMN input_attachments.content_sha256 IS '附件内容 SHA-256 摘要，用于幂等和完整性校验';
COMMENT ON COLUMN input_attachments.storage_key IS 'MinIO 对象键，禁止直接返回给前端';
COMMENT ON COLUMN input_attachments.recognition_engine IS '本次附件实际采用的识别方式：none、local 或 mineru';
COMMENT ON COLUMN input_attachments.status IS '附件状态：queued、parsing、ready、failed 或 rejected';
COMMENT ON COLUMN input_attachments.error_code IS '附件上传或识别失败错误码';
COMMENT ON COLUMN input_attachments.error_message IS '可向用户展示的脱敏失败说明';
COMMENT ON COLUMN input_attachments.created_at IS '附件上传时间';
COMMENT ON COLUMN input_attachments.updated_at IS '附件状态最后更新时间';
COMMENT ON COLUMN input_attachments.expires_at IS '附件及解析结果计划清理时间；永久保存时为空';
COMMENT ON COLUMN attachment_parse_results.id IS '附件解析结果主键';
COMMENT ON COLUMN attachment_parse_results.attachment_id IS '关联附件主键，每个附件仅保留一个当前解析结果';
COMMENT ON COLUMN attachment_parse_results.parser_type IS '实际解析器类型：local 或 mineru';
COMMENT ON COLUMN attachment_parse_results.parser_version IS '解析器实现或外部服务版本标识';
COMMENT ON COLUMN attachment_parse_results.parser_config_hash IS '脱敏解析配置的 SHA-256 摘要，用于幂等判断';
COMMENT ON COLUMN attachment_parse_results.content_storage_key IS '解析 Markdown 的 MinIO 对象键，禁止直接返回给前端';
COMMENT ON COLUMN attachment_parse_results.character_count IS '截断前解析正文字符数';
COMMENT ON COLUMN attachment_parse_results.truncated IS '解析正文是否因平台字符上限被截断';
COMMENT ON COLUMN attachment_parse_results.status IS '解析状态：parsing、ready 或 failed';
COMMENT ON COLUMN attachment_parse_results.error_code IS '解析失败错误码';
COMMENT ON COLUMN attachment_parse_results.error_message IS '可向用户展示的脱敏解析失败说明';
COMMENT ON COLUMN attachment_parse_results.created_at IS '解析结果创建时间';
COMMENT ON COLUMN attachment_parse_results.updated_at IS '解析结果最后更新时间';
