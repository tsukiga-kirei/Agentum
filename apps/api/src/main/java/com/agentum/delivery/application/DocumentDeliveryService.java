package com.agentum.delivery.application;

import com.agentum.asset.application.AssetManagementService;
import com.agentum.delivery.domain.DeliveryRecordEntity;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.RequestIds;
import com.agentum.system.domain.SystemCapabilityEntity;
import com.agentum.system.infrastructure.SystemCapabilityRepository;
import com.agentum.system.infrastructure.TenantCapabilityGrantRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(DocumentDeliveryService.class);
    private static final long BYTES_PER_MB = 1024L * 1024L;

    private final MarkdownDocxRenderer renderer;
    private final DocumentDeliveryStorage storage;
    private final SystemCapabilityRepository systemCapabilityRepository;
    private final TenantCapabilityGrantRepository tenantCapabilityGrantRepository;
    private final AssetManagementService assetManagementService;
    private final Clock clock;

    public DocumentDeliveryService(
        MarkdownDocxRenderer renderer,
        DocumentDeliveryStorage storage,
        SystemCapabilityRepository systemCapabilityRepository,
        TenantCapabilityGrantRepository tenantCapabilityGrantRepository,
        AssetManagementService assetManagementService,
        Clock clock
    ) {
        this.renderer = renderer;
        this.storage = storage;
        this.systemCapabilityRepository = systemCapabilityRepository;
        this.tenantCapabilityGrantRepository = tenantCapabilityGrantRepository;
        this.assetManagementService = assetManagementService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DocumentDeliveryFile preview(UUID tenantId, UUID operatorUserId, DocumentDeliveryPreviewCommand command) {
        validateDocumentCapabilityForDesigner(tenantId, operatorUserId, command.capabilityId());
        DocumentDeliveryStyle style = DocumentDeliveryStyle.from(command.style());
        String fileName = DocumentDeliveryStorage.sanitizeFileName(firstNonBlank(command.fileName(), "Word文档交付预览.docx"));
        byte[] bytes = renderer.render(command.markdown(), style);
        log.info(
            "Word 文档交付预览已生成 tenantId={} userId={} fileName={} sizeBytes={} requestId={}",
            tenantId,
            operatorUserId,
            fileName,
            bytes.length,
            RequestIds.current()
        );
        return new DocumentDeliveryFile(fileName, MarkdownDocxRenderer.DOCX_CONTENT_TYPE, bytes);
    }

    public Map<String, Object> generateRuntimeDocument(
        UUID tenantId,
        UUID operatorUserId,
        UUID recordId,
        SystemCapabilityEntity capability,
        Map<String, Object> nodeConfig,
        Map<String, Object> payload
    ) {
        if (!isDocumentCapability(capability)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DELIVERY_DOCUMENT_CAPABILITY_INVALID", "交付能力不是 Word 文档交付通道");
        }
        Map<String, Object> variables = payload == null ? Map.of() : payload;
        Map<String, Object> config = nodeConfig == null ? Map.of() : nodeConfig;
        Map<String, Object> styleConfig = mergedStyleConfig(capability.getConfig(), config);
        DocumentDeliveryStyle style = DocumentDeliveryStyle.from(styleConfig);
        Map<String, Object> templateVariables = enrichTemplateVariables(variables);
        String markdown = resolveMarkdown(config, templateVariables);
        String fileName = renderTextTemplate(firstNonBlank(stringValue(config.get("fileNameTemplate")), "交付文档-{{runNumber}}.docx"), templateVariables);
        byte[] bytes = renderer.render(markdown, style);
        enforceMaxFileSize(capability, bytes.length, tenantId, operatorUserId, recordId);
        DocumentDeliveryArtifact artifact = storage.store(tenantId, recordId, fileName, bytes);
        int retentionDays = retentionDays(capability.getConfig());
        log.info(
            "Word 文档交付已生成 tenantId={} userId={} recordId={} fileName={} sizeBytes={} requestId={}",
            tenantId,
            operatorUserId,
            recordId,
            artifact.fileName(),
            artifact.sizeBytes(),
            RequestIds.current()
        );
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("adapter", "word_document");
        result.put("deliveryChannel", "document");
        result.put("documentKind", "word");
        result.put("fileName", artifact.fileName());
        result.put("contentType", artifact.contentType());
        result.put("sizeBytes", artifact.sizeBytes());
        result.put("storageProvider", "minio");
        result.put("storageKey", artifact.storageKey());
        result.put("retentionDays", retentionDays);
        result.put("expiresAt", clock.instant().plusSeconds(retentionDays * 86_400L).toString());
        result.put("downloadUrl", "/api/tenants/" + tenantId + "/delivery-records/" + recordId + "/download");
        result.put("style", style.toMap());
        return result;
    }

    public DocumentDeliveryFile readRecordFile(DeliveryRecordEntity record) {
        if ("expired".equals(record.getStatus())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "DELIVERY_DOCUMENT_EXPIRED", "交付文档已超过保留期限并清理");
        }
        if (!"success".equals(record.getStatus())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DELIVERY_DOCUMENT_NOT_READY", "交付文档尚未生成成功");
        }
        Map<String, Object> result = record.getResultSnapshot() == null ? Map.of() : record.getResultSnapshot();
        String storageKey = stringValue(result.get("storageKey"));
        if (storageKey == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DELIVERY_DOCUMENT_NOT_FOUND", "交付记录没有可下载的 Word 文档");
        }
        String fileName = firstNonBlank(stringValue(result.get("fileName")), record.getTitle() + ".docx");
        return storage.read(storageKey, fileName);
    }

    private void validateDocumentCapabilityForDesigner(UUID tenantId, UUID operatorUserId, String rawCapabilityId) {
        String capabilityIdText = rawCapabilityId == null ? "" : rawCapabilityId.trim();
        if (capabilityIdText.isBlank() || "none".equalsIgnoreCase(capabilityIdText) || "custom".equalsIgnoreCase(capabilityIdText)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DELIVERY_DOCUMENT_CAPABILITY_REQUIRED", "请选择 Word 文档交付能力");
        }
        UUID capabilityId = parseUuid(capabilityIdText)
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "DELIVERY_DOCUMENT_CAPABILITY_INVALID", "Word 文档交付能力标识不合法"));
        SystemCapabilityEntity capability = systemCapabilityRepository.findById(capabilityId)
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "DELIVERY_DOCUMENT_CAPABILITY_NOT_FOUND", "Word 文档交付能力不存在"));
        if (!"active".equals(capability.getStatus()) || !"delivery".equals(capability.getCapabilityType()) || !isDocumentCapability(capability)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DELIVERY_DOCUMENT_CAPABILITY_INVALID", "所选能力不是已启用的 Word 文档交付能力");
        }
        boolean tenantGranted = tenantCapabilityGrantRepository.findByTenantIdAndCapabilityId(tenantId, capabilityId)
            .filter(grant -> "enabled".equals(grant.getStatus()))
            .isPresent();
        if (!tenantGranted || !assetManagementService.canUseSystemCapabilityReference(tenantId, operatorUserId, capabilityId, "delivery")) {
            log.warn(
                "Word 文档预览被拒绝：交付能力未向当前主体开放 tenantId={} userId={} capabilityId={} requestId={}",
                tenantId,
                operatorUserId,
                capabilityId,
                RequestIds.current()
            );
            throw new ApiException(HttpStatus.FORBIDDEN, "DELIVERY_DOCUMENT_CAPABILITY_NOT_ASSIGNED", "Word 文档交付能力未分配给当前账号");
        }
    }

    private boolean isDocumentCapability(SystemCapabilityEntity capability) {
        if (capability == null || capability.getConfig() == null) {
            return false;
        }
        Map<String, Object> config = capability.getConfig();
        String channel = stringValue(config.get("deliveryChannel"));
        String kind = stringValue(config.get("documentKind"));
        return "document".equals(channel) || "word".equals(kind) || "word_document".equals(channel);
    }

    private Map<String, Object> mergedStyleConfig(Map<String, Object> capabilityConfig, Map<String, Object> nodeConfig) {
        Map<String, Object> result = new LinkedHashMap<>();
        Object defaultStyle = capabilityConfig == null ? null : capabilityConfig.get("defaultStyle");
        if (defaultStyle instanceof Map<?, ?> styleMap) {
            copyMap(styleMap, result);
        }
        Object nodeStyle = nodeConfig == null ? null : nodeConfig.get("documentStyle");
        if (nodeStyle instanceof Map<?, ?> styleMap) {
            copyMap(styleMap, result);
        }
        for (String key : List.of(
            "chineseFont",
            "latinFont",
            "bodyFontSize",
            "heading1FontSize",
            "heading2FontSize",
            "heading3FontSize",
            "heading1ChineseFont",
            "heading1LatinFont",
            "heading2ChineseFont",
            "heading2LatinFont",
            "heading3ChineseFont",
            "heading3LatinFont",
            "tableChineseFont",
            "tableLatinFont",
            "tableFontSize",
            "tableCellAlignment",
            "lineSpacing",
            "firstLineIndentChars",
            "paragraphSpacingBefore",
            "paragraphSpacingAfter",
            "marginTopCm",
            "marginBottomCm",
            "marginLeftCm",
            "marginRightCm",
            "titleCentered",
            "headingFirstLineIndent"
        )) {
            if (nodeConfig != null && nodeConfig.containsKey(key)) {
                result.put(key, nodeConfig.get(key));
            }
        }
        return result;
    }

    private void copyMap(Map<?, ?> source, Map<String, Object> target) {
        source.forEach((key, value) -> {
            if (key != null) {
                target.put(key.toString(), value);
            }
        });
    }

    private String resolveMarkdown(Map<String, Object> config, Map<String, Object> variables) {
        String markdownContent = stringValue(config.get("markdownContent"));
        if (markdownContent == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DELIVERY_DOCUMENT_TEMPLATE_REQUIRED", "Word 文档交付必须配置交付正文模板");
        }
        return renderMarkdownTemplate(markdownContent, variables);
    }

    private String objectToMarkdown(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Map<?, ?> map) {
            for (String key : List.of("final_answer", "agent_response", "summary", "content", "text")) {
                Object nested = map.get(key);
                if (nested != null) {
                    return objectToMarkdown(nested);
                }
            }
            StringBuilder builder = new StringBuilder();
            map.forEach((key, item) -> builder.append("- **").append(key).append("**：").append(item == null ? "" : item).append("\n"));
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

    private void enforceMaxFileSize(SystemCapabilityEntity capability, long sizeBytes, UUID tenantId, UUID operatorUserId, UUID recordId) {
        int maxFileSizeMb = maxFileSizeMb(capability.getConfig());
        long maxBytes = maxFileSizeMb * BYTES_PER_MB;
        if (sizeBytes <= maxBytes) {
            return;
        }
        log.warn(
            "Word 文档交付文件超过系统能力限制 tenantId={} userId={} recordId={} capabilityId={} sizeBytes={} maxFileSizeMb={} requestId={}",
            tenantId,
            operatorUserId,
            recordId,
            capability.getId(),
            sizeBytes,
            maxFileSizeMb,
            RequestIds.current()
        );
        throw new ApiException(HttpStatus.BAD_REQUEST, "DELIVERY_DOCUMENT_FILE_TOO_LARGE", "Word 文档超过系统管理员配置的最大文件大小");
    }

    private int maxFileSizeMb(Map<String, Object> config) {
        return readInt(config == null ? null : config.get("maxFileSizeMb"), 20, 1, 200);
    }

    private int retentionDays(Map<String, Object> config) {
        return readInt(config == null ? null : config.get("retentionDays"), 180, 1, 3650);
    }

    private int readInt(Object value, int fallback, int min, int max) {
        if (value == null) {
            return fallback;
        }
        try {
            int parsed = value instanceof Number number ? number.intValue() : Integer.parseInt(value.toString().trim());
            return Math.min(max, Math.max(min, parsed));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private Map<String, Object> enrichTemplateVariables(Map<String, Object> variables) {
        Map<String, Object> result = new LinkedHashMap<>(variables);
        LocalDate today = LocalDate.now(clock);
        result.putIfAbsent("date", today.format(DateTimeFormatter.ISO_LOCAL_DATE));
        result.putIfAbsent("dateCompact", today.format(DateTimeFormatter.BASIC_ISO_DATE));
        result.putIfAbsent("year", String.valueOf(today.getYear()));
        result.putIfAbsent("month", "%02d".formatted(today.getMonthValue()));
        result.putIfAbsent("day", "%02d".formatted(today.getDayOfMonth()));
        return result;
    }

    private String renderTextTemplate(String template, Map<String, Object> variables) {
        String result = template == null ? "" : template;
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            String pattern = "\\{\\{\\s*" + Pattern.quote(entry.getKey()) + "\\s*\\}\\}";
            String replacement = entry.getValue() == null ? "" : entry.getValue().toString();
            result = result.replaceAll(pattern, Matcher.quoteReplacement(replacement));
        }
        return result;
    }

    private String renderMarkdownTemplate(String template, Map<String, Object> variables) {
        String result = template == null ? "" : template;
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            String pattern = "\\{\\{\\s*" + Pattern.quote(entry.getKey()) + "\\s*\\}\\}";
            result = result.replaceAll(pattern, Matcher.quoteReplacement(objectToMarkdown(entry.getValue())));
        }
        return result;
    }

    private Optional<UUID> parseUuid(String value) {
        try {
            return value == null || value.isBlank() ? Optional.empty() : Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isBlank() ? null : text;
    }
}
