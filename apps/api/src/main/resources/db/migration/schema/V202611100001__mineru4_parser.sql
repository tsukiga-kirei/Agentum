-- 解析策略与服务协议分别选择，混合模式也能明确使用 4.0；已有配置保持原协议。
ALTER TABLE attachment_recognition_settings
    ADD COLUMN mineru_parser VARCHAR(20) NOT NULL DEFAULT 'mineru',
    ADD COLUMN mineru_tier VARCHAR(20) NOT NULL DEFAULT 'standard',
    ADD COLUMN mineru_ocr_mode VARCHAR(20) NOT NULL DEFAULT 'auto',
    ADD CONSTRAINT ck_attachment_mineru_parser CHECK (mineru_parser IN ('mineru', 'mineru4')),
    ADD CONSTRAINT ck_attachment_mineru_tier CHECK (mineru_tier IN ('flash', 'basic', 'standard', 'advanced')),
    ADD CONSTRAINT ck_attachment_mineru_ocr_mode CHECK (mineru_ocr_mode IN ('auto', 'txt', 'ocr'));

COMMENT ON COLUMN attachment_recognition_settings.mineru_parser IS '复杂识别协议：mineru 旧版，mineru4 为 4.0 上传与解析任务协议';
COMMENT ON COLUMN attachment_recognition_settings.mineru_tier IS 'MinerU 4.0 的 PDF/图片档位；原生文档固定使用 flash';
COMMENT ON COLUMN attachment_recognition_settings.mineru_ocr_mode IS 'MinerU 4.0 OCR 模式：auto、txt、ocr';
