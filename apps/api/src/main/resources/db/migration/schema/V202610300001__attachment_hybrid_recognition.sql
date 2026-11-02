ALTER TABLE attachment_recognition_settings DROP CONSTRAINT ck_attachment_recognition_engine;
ALTER TABLE attachment_recognition_settings ADD CONSTRAINT ck_attachment_recognition_engine
    CHECK (recognition_engine IN ('local', 'mineru', 'hybrid'));
ALTER TABLE input_attachments DROP CONSTRAINT ck_input_attachment_engine;
ALTER TABLE input_attachments ADD CONSTRAINT ck_input_attachment_engine
    CHECK (recognition_engine IN ('none', 'local', 'mineru', 'hybrid'));
ALTER TABLE attachment_parse_results ADD COLUMN fallback_reason VARCHAR(100);

COMMENT ON COLUMN attachment_recognition_settings.recognition_engine IS '识别策略：local 本地，mineru 全部复杂识别，hybrid 本地优先并按白名单升级复杂识别';
COMMENT ON COLUMN input_attachments.recognition_engine IS '上传时确定的识别策略：none、local、mineru 或 hybrid';
COMMENT ON COLUMN attachment_parse_results.parser_type IS '实际最后使用或尝试的解析器，如 local-docx、local-doc、mineru';
COMMENT ON COLUMN attachment_parse_results.fallback_reason IS '兼容解析或升级原因：DOCX_AS_DOC、LOCAL_UNSUPPORTED、LOCAL_TEXT_EMPTY、LOCAL_PARSE_FAILED';
