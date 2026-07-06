package com.agentum.delivery.application;

import com.agentum.delivery.application.ExcelWorkbookRenderer.ExcelSheetRenderSpec;
import com.agentum.delivery.application.ExcelWorkbookRenderer.ExcelWorkbookRenderResult;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.RequestIds;
import com.agentum.system.domain.SystemCapabilityEntity;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ExcelDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(ExcelDeliveryService.class);
    private static final long BYTES_PER_MB = 1024L * 1024L;
    private static final Pattern TEMPLATE_VARIABLE_PATTERN = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*}}");

    private final ExcelWorkbookRenderer renderer;
    private final DocumentDeliveryStorage storage;
    private final Clock clock;

    public ExcelDeliveryService(
        ExcelWorkbookRenderer renderer,
        DocumentDeliveryStorage storage,
        Clock clock
    ) {
        this.renderer = renderer;
        this.storage = storage;
        this.clock = clock;
    }

    public Map<String, Object> generateRuntimeWorkbook(
        UUID tenantId,
        UUID operatorUserId,
        UUID recordId,
        SystemCapabilityEntity capability,
        Map<String, Object> nodeConfig,
        Map<String, Object> payload
    ) {
        if (!isExcelCapability(capability)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DELIVERY_EXCEL_CAPABILITY_INVALID", "交付能力不是 Excel 工作簿交付通道");
        }
        Map<String, Object> variables = payload == null ? Map.of() : payload;
        Map<String, Object> config = nodeConfig == null ? Map.of() : nodeConfig;
        Map<String, Object> templateVariables = enrichTemplateVariables(variables);
        List<ExcelSheetRenderSpec> sheets = readSheetSpecs(config, templateVariables);
        String fileName = renderTextTemplate(firstNonBlank(stringValue(config.get("fileNameTemplate")), "交付表格-{{runNumber}}.xlsx"), templateVariables);
        ExcelWorkbookRenderResult workbook = renderer.render(sheets);
        enforceMaxFileSize(capability, workbook.bytes().length, tenantId, operatorUserId, recordId);
        DocumentDeliveryArtifact artifact = storage.store(tenantId, recordId, fileName, ExcelWorkbookRenderer.XLSX_CONTENT_TYPE, workbook.bytes());
        int retentionDays = retentionDays(capability.getConfig());
        log.info(
            "Excel 工作簿交付已生成 tenantId={} userId={} recordId={} fileName={} sheetCount={} sizeBytes={} requestId={}",
            tenantId,
            operatorUserId,
            recordId,
            artifact.fileName(),
            workbook.sheetCount(),
            artifact.sizeBytes(),
            RequestIds.current()
        );
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("adapter", "excel_workbook");
        result.put("deliveryChannel", "document");
        result.put("documentKind", "excel");
        result.put("fileName", artifact.fileName());
        result.put("contentType", artifact.contentType());
        result.put("sizeBytes", artifact.sizeBytes());
        result.put("sheetCount", workbook.sheetCount());
        result.put("warnings", workbook.warnings());
        result.put("warningCount", workbook.warnings().size());
        result.put("storageProvider", "minio");
        result.put("storageKey", artifact.storageKey());
        result.put("retentionDays", retentionDays);
        result.put("expiresAt", clock.instant().plusSeconds(retentionDays * 86_400L).toString());
        result.put("downloadUrl", "/api/tenants/" + tenantId + "/delivery-records/" + recordId + "/download");
        return result;
    }

    private List<ExcelSheetRenderSpec> readSheetSpecs(Map<String, Object> config, Map<String, Object> variables) {
        List<Map<String, Object>> rawSheets = readMapList(firstNonNull(config.get("excelSheets"), config.get("sheets")));
        if (rawSheets.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DELIVERY_EXCEL_SHEETS_REQUIRED", "Excel 交付必须至少配置一个 Sheet 页");
        }
        List<ExcelSheetRenderSpec> result = new ArrayList<>();
        for (int index = 0; index < rawSheets.size(); index++) {
            Map<String, Object> sheet = rawSheets.get(index);
            String name = firstNonBlank(stringValue(sheet.get("name")), "Sheet" + (index + 1));
            String template = firstNonBlank(
                stringValue(sheet.get("bodyTemplate")),
                stringValue(sheet.get("markdownContent")),
                stringValue(sheet.get("body")),
                "暂无内容。"
            );
            String body = renderTextTemplate(template, variables);
            result.add(new ExcelSheetRenderSpec(
                name,
                body,
                firstNonBlank(stringValue(sheet.get("startCell")), "A1"),
                firstNonBlank(stringValue(sheet.get("defaultCellType")), "text"),
                readMap(sheet.get("tableStyle")),
                readMapList(sheet.get("columnRules")),
                readMapList(sheet.get("rowRules")),
                readMapList(sheet.get("cellRules"))
            ));
        }
        return result;
    }

    private boolean isExcelCapability(SystemCapabilityEntity capability) {
        if (capability == null || capability.getConfig() == null) {
            return false;
        }
        Map<String, Object> config = capability.getConfig();
        String channel = stringValue(config.get("deliveryChannel"));
        String kind = stringValue(config.get("documentKind"));
        return "excel".equals(channel) || ("document".equals(channel) && "excel".equals(kind));
    }

    private void enforceMaxFileSize(SystemCapabilityEntity capability, long sizeBytes, UUID tenantId, UUID operatorUserId, UUID recordId) {
        int maxFileSizeMb = parsePositiveInt(capability.getConfig().get("maxFileSizeMb"), 20, 1, 200);
        if (sizeBytes <= maxFileSizeMb * BYTES_PER_MB) {
            return;
        }
        log.warn(
            "Excel 工作簿交付文件过大 tenantId={} userId={} recordId={} sizeBytes={} maxFileSizeMb={} requestId={}",
            tenantId,
            operatorUserId,
            recordId,
            sizeBytes,
            maxFileSizeMb,
            RequestIds.current()
        );
        throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "DELIVERY_EXCEL_FILE_TOO_LARGE", "Excel 工作簿超过系统配置的最大文件大小");
    }

    private int retentionDays(Map<String, Object> config) {
        return parsePositiveInt(config == null ? null : config.get("retentionDays"), 180, 1, 3650);
    }

    private int parsePositiveInt(Object value, int fallback, int min, int max) {
        int parsed = fallback;
        if (value instanceof Number number) {
            parsed = number.intValue();
        } else if (value != null && !value.toString().isBlank()) {
            try {
                parsed = Integer.parseInt(value.toString().trim());
            } catch (NumberFormatException ignored) {
                parsed = fallback;
            }
        }
        return Math.max(min, Math.min(max, parsed));
    }

    private Map<String, Object> enrichTemplateVariables(Map<String, Object> variables) {
        Map<String, Object> result = new LinkedHashMap<>(variables == null ? Map.of() : variables);
        Instant now = clock.instant();
        var localDate = now.atZone(ZoneId.systemDefault()).toLocalDate();
        result.putIfAbsent("date", DateTimeFormatter.ISO_LOCAL_DATE.format(localDate));
        result.putIfAbsent("dateCompact", DateTimeFormatter.BASIC_ISO_DATE.format(localDate));
        return result;
    }

    private String renderTextTemplate(String template, Map<String, Object> variables) {
        if (template == null || template.isBlank()) {
            return "";
        }
        Matcher matcher = TEMPLATE_VARIABLE_PATTERN.matcher(template);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(objectToDisplayText(variables.get(name))));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String objectToDisplayText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Map<?, ?> map) {
            for (String key : List.of("final_answer", "agent_response", "summary", "content", "text")) {
                Object nested = map.get(key);
                if (nested != null) {
                    return objectToDisplayText(nested);
                }
            }
            StringBuilder builder = new StringBuilder();
            map.forEach((key, item) -> builder.append(key == null ? "" : key).append("：").append(item == null ? "" : item).append("\n"));
            return builder.toString();
        }
        if (value instanceof Iterable<?> iterable) {
            StringBuilder builder = new StringBuilder();
            for (Object item : iterable) {
                builder.append("- ").append(item == null ? "" : item).append("\n");
            }
            return builder.toString();
        }
        return value.toString();
    }

    private Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readMapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add(new LinkedHashMap<>((Map<String, Object>) map));
            }
        }
        return result;
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
