-- MCP 工具清单改由标准协议 tools/list 读取，移除历史自定义预览地址字段。
UPDATE system_capabilities
SET config = config - 'toolCatalogUrl'
WHERE capability_type = 'mcp'
  AND config ? 'toolCatalogUrl';
