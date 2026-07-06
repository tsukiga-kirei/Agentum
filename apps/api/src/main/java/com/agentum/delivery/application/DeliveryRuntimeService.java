package com.agentum.delivery.application;

import com.agentum.delivery.domain.DeliveryRecordEntity;
import com.agentum.delivery.infrastructure.DeliveryRecordRepository;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.RequestIds;
import com.agentum.system.domain.SystemCapabilityEntity;
import com.agentum.system.infrastructure.SystemCapabilityRepository;
import com.agentum.system.infrastructure.TenantCapabilityGrantRepository;
import com.agentum.workflow.application.WorkflowRuntimeSystemVariables;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
public class DeliveryRuntimeService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryRuntimeService.class);
    /** 设计器历史占位值：未上线阶段不保留兜底直出，运行时统一要求绑定具体交付能力。 */
    private static final Set<String> CAPABILITY_SENTINEL_VALUES = Set.of("none", "custom");

    private final SystemCapabilityRepository systemCapabilityRepository;
    private final TenantCapabilityGrantRepository tenantCapabilityGrantRepository;
    private final DeliveryRecordRepository deliveryRecordRepository;
    private final EmailDeliveryService emailDeliveryService;
    private final DocumentDeliveryService documentDeliveryService;
    private final ExcelDeliveryService excelDeliveryService;
    private final DeliveryContentTemplateRenderer contentTemplateRenderer;
    private final RestClient restClient;
    private final Clock clock;

    public DeliveryRuntimeService(
        SystemCapabilityRepository systemCapabilityRepository,
        TenantCapabilityGrantRepository tenantCapabilityGrantRepository,
        DeliveryRecordRepository deliveryRecordRepository,
        EmailDeliveryService emailDeliveryService,
        DocumentDeliveryService documentDeliveryService,
        ExcelDeliveryService excelDeliveryService,
        DeliveryContentTemplateRenderer contentTemplateRenderer,
        Clock clock
    ) {
        this.systemCapabilityRepository = systemCapabilityRepository;
        this.tenantCapabilityGrantRepository = tenantCapabilityGrantRepository;
        this.deliveryRecordRepository = deliveryRecordRepository;
        this.emailDeliveryService = emailDeliveryService;
        this.documentDeliveryService = documentDeliveryService;
        this.excelDeliveryService = excelDeliveryService;
        this.contentTemplateRenderer = contentTemplateRenderer;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(8000);
        requestFactory.setReadTimeout(30000);
        this.restClient = RestClient.builder()
            .requestFactory(requestFactory)
            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .build();
        this.clock = clock;
    }

    public DeliveryRuntimeResult execute(DeliveryRuntimeRequest request) {
        if (isMultipleDelivery(request.nodeConfig())) {
            return executeMultipleDelivery(request);
        }
        if (isDirectDelivery(request.nodeConfig())) {
            return executeDirectDelivery(request, request.nodeConfig(), "", "");
        }
        return executeCapabilityDelivery(request, request.nodeConfig(), "", "");
    }

    private DeliveryRuntimeResult executeMultipleDelivery(DeliveryRuntimeRequest request) {
        Map<String, Object> variables = enrichRuntimeVariables(request);
        String policy = firstNonBlank(stringValue(request.nodeConfig().get("deliveryExecutionPolicy")), "all");
        List<Map<String, Object>> deliveryItems = deliveryItems(request.nodeConfig());
        List<Map<String, Object>> executedItems = new ArrayList<>();
        for (int index = 0; index < deliveryItems.size(); index++) {
            Map<String, Object> item = deliveryItems.get(index);
            if (!booleanValue(item.getOrDefault("enabled", true))) {
                continue;
            }
            if ("conditional".equals(policy) && !matchesDeliveryTrigger(item, variables)) {
                continue;
            }
            Map<String, Object> itemConfig = itemConfig(request.nodeConfig(), item);
            String itemId = firstNonBlank(stringValue(item.get("id")), "delivery_item_" + (index + 1));
            String itemName = firstNonBlank(stringValue(item.get("name")), "交付项 " + (index + 1));
            DeliveryRuntimeResult itemResult = executeDeliveryItem(request, itemConfig, itemId, itemName);
            Map<String, Object> output = new LinkedHashMap<>(itemResult.outputs());
            output.put("itemId", itemId);
            output.put("itemName", itemName);
            executedItems.add(output);
        }
        if (executedItems.isEmpty()) {
            log.info(
                "多交付节点未命中任何交付项 tenantId={} runId={} nodeRunId={} policy={} requestId={}",
                request.run().getTenantId(),
                request.run().getId(),
                request.nodeRun().getId(),
                policy,
                RequestIds.current()
            );
            return new DeliveryRuntimeResult(Map.of(
                "deliveryStatus", "skipped",
                "deliveryRecords", List.of(),
                "summary", "多交付未命中任何交付项"
            ));
        }
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("deliveryStatus", "success");
        outputs.put("deliveryRecords", executedItems);
        outputs.put("summary", "已执行 " + executedItems.size() + " 个交付项");
        Object firstRecordId = executedItems.getFirst().get("deliveryRecordId");
        if (firstRecordId != null) {
            outputs.put("deliveryRecordId", firstRecordId);
        }
        return new DeliveryRuntimeResult(outputs);
    }

    private DeliveryRuntimeResult executeDeliveryItem(
        DeliveryRuntimeRequest request,
        Map<String, Object> itemConfig,
        String itemId,
        String itemName
    ) {
        if (isDirectDelivery(itemConfig)) {
            return executeDirectDelivery(request, itemConfig, itemId, itemName);
        }
        return executeCapabilityDelivery(request, itemConfig, itemId, itemName);
    }

    private DeliveryRuntimeResult executeCapabilityDelivery(
        DeliveryRuntimeRequest request,
        Map<String, Object> nodeConfig,
        String itemId,
        String itemName
    ) {
        SystemCapabilityEntity capability = resolveDeliveryCapability(request, nodeConfig);
        String deliveryType = firstNonBlank(
            stringValue(nodeConfig.get("deliveryType")),
            stringValue(capability.getConfig().get("deliveryChannel")),
            stringValue(capability.getConfig().get("sourceType")),
            "delivery"
        );
        Map<String, Object> payload = buildPayload(request, nodeConfig, enrichRuntimeVariables(request), null);
        String title = firstNonBlank(stringValue(nodeConfig.get("subject")), stringValue(nodeConfig.get("title")), itemName, request.run().getTitle());
        String target = firstNonBlank(stringValue(nodeConfig.get("deliveryTarget")), stringValue(capability.getConfig().get("endpointUrl")), "业务交付目标");
        DeliveryRecordEntity record = DeliveryRecordEntity.started(
            request.run(),
            request.nodeRun(),
            capability,
            deliveryType,
            truncate(target, 300),
            truncate(title, 200),
            sanitizeMap(payload),
            request.operatorUserId(),
            clock.instant()
        );
        deliveryRecordRepository.save(record);

        try {
            Map<String, Object> result = dispatchCapability(capability, deliveryType, title, payload, request, nodeConfig, record.getId());
            record.succeed(result, clock.instant());
            deliveryRecordRepository.save(record);
            Map<String, Object> outputs = new LinkedHashMap<>();
            outputs.put("deliveryRecordId", record.getId().toString());
            outputs.put("deliveryStatus", "success");
            outputs.put("deliveryResult", result);
            outputs.put("summary", deliverySummary(deliveryType, result, title));
            if (!itemId.isBlank()) {
                outputs.put("itemId", itemId);
            }
            if (!itemName.isBlank()) {
                outputs.put("itemName", itemName);
            }
            return new DeliveryRuntimeResult(outputs);
        } catch (ApiException exception) {
            record.fail(exception.getCode(), exception.getMessage(), clock.instant());
            deliveryRecordRepository.save(record);
            throw exception;
        } catch (RuntimeException exception) {
            record.fail("DELIVERY_RUNTIME_FAILED", "交付执行失败", clock.instant());
            deliveryRecordRepository.save(record);
            log.warn(
                "交付节点执行失败 tenantId={} runId={} nodeRunId={} capabilityId={} requestId={}",
                request.run().getTenantId(),
                request.run().getId(),
                request.nodeRun().getId(),
                capability.getId(),
                RequestIds.current(),
                exception
            );
            throw new ApiException(HttpStatus.BAD_GATEWAY, "DELIVERY_RUNTIME_FAILED", "交付执行失败，请检查能力配置");
        }
    }

    private DeliveryRuntimeResult executeDirectDelivery(
        DeliveryRuntimeRequest request,
        Map<String, Object> nodeConfig,
        String itemId,
        String itemName
    ) {
        String template = resolveDirectDeliveryTemplate(nodeConfig);
        if (template.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DELIVERY_DIRECT_TEMPLATE_REQUIRED", "请配置直接交付内容模板");
        }
        Map<String, Object> variables = enrichRuntimeVariables(request);
        String content = contentTemplateRenderer.render(template, variables);
        if (content.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DELIVERY_DIRECT_CONTENT_EMPTY", "直接交付内容渲染结果为空，请检查模板与上游变量");
        }
        String title = firstNonBlank(
            stringValue(nodeConfig.get("subject")),
            stringValue(nodeConfig.get("title")),
            itemName,
            request.run().getTitle()
        );
        Map<String, Object> payload = buildPayload(request, nodeConfig, variables, content);
        DeliveryRecordEntity record = DeliveryRecordEntity.started(
            request.run(),
            request.nodeRun(),
            null,
            "direct",
            truncate(content, 300),
            truncate(title, 200),
            sanitizeMap(payload),
            request.operatorUserId(),
            clock.instant()
        );
        deliveryRecordRepository.save(record);

        Map<String, Object> deliveryPayload = Map.of(
            "body", content,
            "deliveryTarget", content
        );
        Map<String, Object> result = Map.of(
            "adapter", "direct",
            "content", content
        );
        record.succeed(result, clock.instant());
        deliveryRecordRepository.save(record);
        log.info(
            "直接交付已生成 tenantId={} runId={} nodeRunId={} recordId={} contentLength={} requestId={}",
            request.run().getTenantId(),
            request.run().getId(),
            request.nodeRun().getId(),
            record.getId(),
            content.length(),
            RequestIds.current()
        );
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("deliveryRecordId", record.getId().toString());
        outputs.put("deliveryStatus", "success");
        outputs.put("deliveryResult", result);
        outputs.put("deliveryPayload", deliveryPayload);
        outputs.put("summary", directDeliverySummary(content));
        if (!itemId.isBlank()) {
            outputs.put("itemId", itemId);
        }
        if (!itemName.isBlank()) {
            outputs.put("itemName", itemName);
        }
        return new DeliveryRuntimeResult(outputs);
    }

    private boolean isDirectDelivery(Map<String, Object> nodeConfig) {
        Map<String, Object> config = nodeConfig == null ? Map.of() : nodeConfig;
        return "direct".equalsIgnoreCase(stringValue(config.get("deliveryMode")))
            || "direct".equalsIgnoreCase(stringValue(config.get("deliveryType")));
    }

    private String resolveDirectDeliveryTemplate(Map<String, Object> nodeConfig) {
        Map<String, Object> config = nodeConfig == null ? Map.of() : nodeConfig;
        return firstNonBlank(
            stringValue(config.get("deliveryContent")),
            stringValue(config.get("deliveryTarget")),
            stringValue(config.get("body")),
            stringValue(config.get("markdownContent"))
        );
    }

    private String directDeliverySummary(String content) {
        String normalized = content == null ? "" : content.replace('\r', ' ').replace('\n', ' ').trim();
        if (normalized.isBlank()) {
            return "直接交付内容已生成";
        }
        return normalized.length() > 120 ? normalized.substring(0, 120) + "…" : normalized;
    }

    private Map<String, Object> enrichRuntimeVariables(DeliveryRuntimeRequest request) {
        Map<String, Object> result = new LinkedHashMap<>(WorkflowRuntimeSystemVariables.from(request.run(), clock));
        result.putAll(request.variables() == null ? Map.of() : request.variables());
        return result;
    }

    private Map<String, Object> dispatchCapability(
        SystemCapabilityEntity capability,
        String deliveryType,
        String title,
        Map<String, Object> payload,
        DeliveryRuntimeRequest request,
        Map<String, Object> nodeConfig,
        UUID recordId
    ) {
        String endpointUrl = stringValue(capability.getConfig().get("endpointUrl"));
        if (isExcelDelivery(deliveryType, capability, nodeConfig)) {
            return excelDeliveryService.generateRuntimeWorkbook(
                request.run().getTenantId(),
                request.operatorUserId(),
                recordId,
                capability,
                nodeConfig,
                payload
            );
        }
        if (isDocumentDelivery(deliveryType, capability, nodeConfig)) {
            return documentDeliveryService.generateRuntimeDocument(
                request.run().getTenantId(),
                request.operatorUserId(),
                recordId,
                capability,
                nodeConfig,
                payload
            );
        }
        if ("email".equals(deliveryType) || "smtp".equals(deliveryType)) {
            EmailDeliveryMessage message = new EmailDeliveryMessage(
                readRecipients(nodeConfig, "to", "recipients", "emailRecipients"),
                readRecipients(nodeConfig, "cc"),
                readRecipients(nodeConfig, "bcc"),
                title,
                firstNonBlank(stringValue(payload.get("body")), stringValue(payload.get("deliveryTarget")), title),
                List.of()
            );
            emailDeliveryService.send(capability, message);
            return Map.of("adapter", "email", "toCount", message.to().size());
        }
        if (!endpointUrl.isBlank()) {
            try {
                String body = restClient.post()
                    .uri(URI.create(endpointUrl))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(String.class);
                return Map.of("adapter", "webhook", "status", "success", "responsePreview", truncate(body, 500));
            } catch (RestClientException exception) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "DELIVERY_WEBHOOK_FAILED", "交付 Webhook 调用失败");
            }
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "DELIVERY_ADAPTER_NOT_CONFIGURED", "交付能力未配置邮箱或 Webhook 适配器");
    }

    private String deliverySummary(String deliveryType, Map<String, Object> result, String title) {
        if (isExcelResult(deliveryType, result)) {
            return "Excel 工作簿已生成：" + firstNonBlank(stringValue(result.get("fileName")), title);
        }
        if (isDocumentResult(deliveryType, result)) {
            return "Word 文档已生成：" + firstNonBlank(stringValue(result.get("fileName")), title);
        }
        return "交付已完成：" + title;
    }

    private boolean isExcelResult(String deliveryType, Map<String, Object> result) {
        return "excel_workbook".equals(deliveryType)
            || "xlsx".equals(deliveryType)
            || (result != null && "excel_workbook".equals(stringValue(result.get("adapter"))));
    }

    private boolean isDocumentResult(String deliveryType, Map<String, Object> result) {
        return "document".equals(deliveryType)
            || "word_document".equals(deliveryType)
            || "docx".equals(deliveryType)
            || (result != null && "word_document".equals(stringValue(result.get("adapter"))));
    }

    private boolean isExcelDelivery(String deliveryType, SystemCapabilityEntity capability, Map<String, Object> nodeConfig) {
        String nodeType = firstNonBlank(
            stringValue(nodeConfig.get("deliveryType")),
            stringValue(nodeConfig.get("documentKind"))
        );
        String channel = stringValue(capability.getConfig().get("deliveryChannel"));
        String kind = stringValue(capability.getConfig().get("documentKind"));
        return "excel_workbook".equals(deliveryType)
            || "xlsx".equals(deliveryType)
            || "excel".equals(channel)
            || "excel".equals(kind)
            || "excel_workbook".equals(nodeType);
    }

    private boolean isDocumentDelivery(String deliveryType, SystemCapabilityEntity capability, Map<String, Object> nodeConfig) {
        String nodeType = firstNonBlank(
            stringValue(nodeConfig.get("deliveryType")),
            stringValue(nodeConfig.get("documentKind"))
        );
        String channel = stringValue(capability.getConfig().get("deliveryChannel"));
        String kind = stringValue(capability.getConfig().get("documentKind"));
        return "document".equals(deliveryType)
            || "word_document".equals(deliveryType)
            || "docx".equals(deliveryType)
            || ("document".equals(channel) && !"excel".equals(kind))
            || "word".equals(kind)
            || "word_document".equals(nodeType);
    }

    private SystemCapabilityEntity resolveDeliveryCapability(DeliveryRuntimeRequest request) {
        return resolveDeliveryCapability(request, request.nodeConfig());
    }

    private SystemCapabilityEntity resolveDeliveryCapability(DeliveryRuntimeRequest request, Map<String, Object> nodeConfig) {
        String capabilityIdText = firstNonBlank(
            stringValue(nodeConfig.get("deliveryCapabilityId")),
            stringValue(nodeConfig.get("capabilityId"))
        );
        if (capabilityIdText.isBlank() || CAPABILITY_SENTINEL_VALUES.contains(capabilityIdText.toLowerCase())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DELIVERY_CAPABILITY_REQUIRED", "请为交付节点配置交付能力");
        }
        UUID capabilityId = parseUuid(capabilityIdText)
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "DELIVERY_CAPABILITY_REQUIRED", "请为交付节点配置交付能力"));
        SystemCapabilityEntity capability = systemCapabilityRepository.findById(capabilityId)
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "DELIVERY_CAPABILITY_NOT_FOUND", "交付能力不存在"));
        if (!"active".equals(capability.getStatus()) || !"delivery".equals(capability.getCapabilityType())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DELIVERY_CAPABILITY_NOT_ACTIVE", "交付能力未启用或类型不匹配");
        }
        boolean granted = tenantCapabilityGrantRepository.findByTenantIdAndCapabilityId(request.run().getTenantId(), capabilityId)
            .filter(grant -> "enabled".equals(grant.getStatus()))
            .isPresent();
        if (!granted) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DELIVERY_CAPABILITY_NOT_ASSIGNED", "该交付能力未分配给当前租户");
        }
        return capability;
    }

    private Map<String, Object> buildPayload(DeliveryRuntimeRequest request, Map<String, Object> variables, String renderedContent) {
        return buildPayload(request, request.nodeConfig(), variables, renderedContent);
    }

    private Map<String, Object> buildPayload(DeliveryRuntimeRequest request, Map<String, Object> nodeConfig, Map<String, Object> variables, String renderedContent) {
        Map<String, Object> payload = new LinkedHashMap<>(variables);
        String template = resolveDirectDeliveryTemplate(nodeConfig);
        String deliveryTarget = renderedContent == null
            ? contentTemplateRenderer.render(firstNonBlank(template, "请查看上游节点输出。"), variables)
            : renderedContent;
        payload.put("deliveryTarget", deliveryTarget);
        payload.put("body", firstNonBlank(stringValue(nodeConfig.get("body")), deliveryTarget));
        payload.put("runId", request.run().getId().toString());
        payload.put("runNumber", request.run().getRunNumber());
        payload.put("nodeRunId", request.nodeRun().getId().toString());
        return payload;
    }

    private boolean isMultipleDelivery(Map<String, Object> nodeConfig) {
        Map<String, Object> config = nodeConfig == null ? Map.of() : nodeConfig;
        return "multiple".equalsIgnoreCase(stringValue(config.get("deliveryConfigMode")));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> deliveryItems(Map<String, Object> nodeConfig) {
        Object rawItems = nodeConfig == null ? null : nodeConfig.get("deliveryItems");
        if (!(rawItems instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> rawMap) {
                result.add(new LinkedHashMap<>((Map<String, Object>) rawMap));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> itemConfig(Map<String, Object> nodeConfig, Map<String, Object> item) {
        Map<String, Object> result = new LinkedHashMap<>(nodeConfig == null ? Map.of() : nodeConfig);
        result.remove("deliveryItems");
        result.remove("deliveryConfigMode");
        result.remove("deliveryExecutionPolicy");
        Object rawConfig = item.get("config");
        if (rawConfig instanceof Map<?, ?> rawMap) {
            result.putAll((Map<String, Object>) rawMap);
        }
        if (stringValue(result.get("deliveryMode")).isBlank()) {
            String capabilityId = stringValue(result.get("deliveryCapabilityId"));
            String inferredMode = "direct".equalsIgnoreCase(stringValue(result.get("deliveryType")))
                || CAPABILITY_SENTINEL_VALUES.contains(capabilityId.toLowerCase())
                ? "direct"
                : !capabilityId.isBlank() ? "capability" : firstNonBlank(stringValue(nodeConfig == null ? null : nodeConfig.get("deliveryMode")), "capability");
            result.put("deliveryMode", inferredMode);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private boolean matchesDeliveryTrigger(Map<String, Object> item, Map<String, Object> variables) {
        Object rawRule = item.get("triggerRule");
        Map<String, Object> rule = rawRule instanceof Map<?, ?> rawMap
            ? new LinkedHashMap<>((Map<String, Object>) rawMap)
            : Map.of("type", "always");
        String type = firstNonBlank(stringValue(rule.get("type")), "always");
        return switch (type) {
            case "cluster_agent_matched" -> hasMeaningfulValue(variables.get(stringValue(rule.get("variableName"))));
            case "input_field_equals" -> stringValue(variables.get(stringValue(rule.get("variableName"))))
                .equals(stringValue(rule.get("expectedValue")));
            case "agent_output_exists" -> hasMeaningfulValue(variables.get(stringValue(rule.get("variableName"))));
            default -> true;
        };
    }

    private boolean hasMeaningfulValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof String text) {
            return !text.trim().isBlank();
        }
        if (value instanceof List<?> list) {
            return !list.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return true;
    }

    private List<String> readRecipients(Map<String, Object> config, String... keys) {
        List<String> recipients = new ArrayList<>();
        for (String key : keys) {
            Object value = config.get(key);
            if (value instanceof List<?> list) {
                list.stream().map(item -> item == null ? "" : item.toString().trim()).filter(text -> !text.isBlank()).forEach(recipients::add);
            } else {
                String text = stringValue(value);
                if (!text.isBlank()) {
                    for (String part : text.split("[,;\\s]+")) {
                        if (!part.isBlank()) {
                            recipients.add(part.trim());
                        }
                    }
                }
            }
            if (!recipients.isEmpty()) {
                return recipients;
            }
        }
        return recipients;
    }

    private Map<String, Object> sanitizeMap(Map<String, Object> source) {
        Map<String, Object> result = new HashMap<>();
        source.forEach((key, value) -> result.put(key, isSensitive(key) ? "***" : value));
        return result;
    }

    private boolean isSensitive(String key) {
        String normalized = key == null ? "" : key.toLowerCase();
        return normalized.contains("password") || normalized.contains("token") || normalized.contains("secret") || normalized.contains("apikey") || normalized.contains("api_key");
    }

    private static Optional<UUID> parseUuid(String value) {
        try {
            return value == null || value.isBlank() ? Optional.empty() : Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = stringValue(value);
        return text.isBlank() || "true".equalsIgnoreCase(text) || "yes".equalsIgnoreCase(text);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
