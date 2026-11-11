-- mineru4 选项统一调整为 4.1.7 Tasks；配置字段及已选值不变，历史迁移保持原校验和。
COMMENT ON COLUMN attachment_recognition_settings.mineru_parser IS '复杂识别协议：mineru 旧版 file_parse，mineru4 为 MinerU 4.1.7 Tasks 协议';
COMMENT ON COLUMN attachment_recognition_settings.mineru_tier IS 'MinerU 4.1.7 PDF/图片档位；原生文档固定使用 flash';
COMMENT ON COLUMN attachment_recognition_settings.mineru_ocr_mode IS 'MinerU 4.1.7 OCR 模式：auto、txt、ocr';
