package com.agentum.delivery.application;

import com.agentum.delivery.domain.DeliveryRecordEntity;
import com.agentum.delivery.infrastructure.DeliveryRecordRepository;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.RequestIds;
import com.agentum.system.domain.SystemCapabilityEntity;
import com.agentum.system.infrastructure.SystemCapabilityRepository;
import com.agentum.system.infrastructure.TenantCapabilityGrantRepository;
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
    /** 设计器占位值：表示未绑定具体交付能力，运行时应降级为站内直接交付。 */
    private static final Set<String> CAPABILITY_SENTINEL_VALUES = Set.of("none", "custom");

    private final SystemCapabilityRepository systemCapabilityRepository;
    private final TenantCapabilityGrantRepository tenantCapabilityGrantRepository;
    private final DeliveryRecordRepository deliveryRecordRepository;
    private final EmailDeliveryService emailDeliveryService;
    private final DocumentDeliveryService documentDeliveryService;
    private final RestClient restClient;
    private final Clock clock;

    public DeliveryRuntimeService(
        SystemCapabilityRepository systemCapabilityRepository,
        TenantCapabilityGrantRepository tenantCapabilityGrantRepository,
        DeliveryRecordRepository deliveryRecordRepository,
        EmailDeliveryService emailDeliveryService,
        DocumentDeliveryService documentDeliveryService,
        Clock clock
    ) {
        this.systemCapabilityRepository = systemCapabilityRepository;
        this.tenantCapabilityGrantRepository = tenantCapabilityGrantRepository;
        this.deliveryRecordRepository = deliveryRecordRepository;
        this.emailDeliveryService = emailDeliveryService;
        this.documentDeliveryService = documentDeliveryService;
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
        if (shouldUseDirectDelivery(request.nodeConfig())) {
            if ("capability".equals(stringValue(request.nodeConfig().get("deliveryMode")))) {
                log.info(
                    "交付节点未绑定有效交付能力，降级为站内直接交付 tenantId={} runId={} nodeRunId={} requestId={}",
                    request.run().getTenantId(),
                    request.run().getId(),
                    request.nodeRun().getId(),
                    RequestIds.current()
                );
            }
            return completeDirectDelivery(request);
        }
        SystemCapabilityEntity capability = resolveDeliveryCapability(request);
        String deliveryType = firstNonBlank(
            stringValue(request.nodeConfig().get("deliveryType")),
            stringValue(capability.getConfig().get("deliveryChannel")),
            stringValue(capability.getConfig().get("sourceType")),
            "delivery"
        );
        Map<String, Object> payload = buildPayload(request);
        String title = firstNonBlank(stringValue(request.nodeConfig().get("subject")), stringValue(request.nodeConfig().get("title")), request.run().getTitle());
        String target = firstNonBlank(stringValue(request.nodeConfig().get("deliveryTarget")), stringValue(capability.getConfig().get("endpointUrl")), "业务交付目标");
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
            Map<String, Object> result = dispatchCapability(capability, deliveryType, title, payload, request, record.getId());
            record.succeed(result, clock.instant());
            deliveryRecordRepository.save(record);
            return new DeliveryRuntimeResult(Map.of(
                "deliveryRecordId", record.getId().toString(),
                "deliveryStatus", "success",
                "deliveryResult", result,
                "summary", deliverySummary(deliveryType, result, title)
            ));
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

    private DeliveryRuntimeResult completeDirectDelivery(DeliveryRuntimeRequest request) {
        Map<String, Object> payload = buildPayload(request);
        String title = firstNonBlank(stringValue(request.nodeConfig().get("title")), request.run().getTitle());
        DeliveryRecordEntity record = DeliveryRecordEntity.started(
            request.run(),
            request.nodeRun(),
            null,
            "direct",
            "站内交付",
            truncate(title, 200),
            sanitizeMap(payload),
            request.operatorUserId(),
            clock.instant()
        );
        record.succeed(Map.of("mode", "direct", "target", "站内交付"), clock.instant());
        deliveryRecordRepository.save(record);
        return new DeliveryRuntimeResult(Map.of(
            "deliveryRecordId", record.getId().toString(),
            "deliveryStatus", "success",
            "deliveryPayload", payload,
            "summary", "已生成站内交付记录：" + title
        ));
    }

    private Map<String, Object> dispatchCapability(
        SystemCapabilityEntity capability,
        String deliveryType,
        String title,
        Map<String, Object> payload,
        DeliveryRuntimeRequest request,
        UUID recordId
    ) {
        String endpointUrl = stringValue(capability.getConfig().get("endpointUrl"));
        if (isDocumentDelivery(deliveryType, capability, request.nodeConfig())) {
            return documentDeliveryService.generateRuntimeDocument(
                request.run().getTenantId(),
                request.operatorUserId(),
                recordId,
                capability,
                request.nodeConfig(),
                payload
            );
        }
        if ("email".equals(deliveryType) || "smtp".equals(deliveryType)) {
            EmailDeliveryMessage message = new EmailDeliveryMessage(
                readRecipients(request.nodeConfig(), "to", "recipients", "emailRecipients"),
                readRecipients(request.nodeConfig(), "cc"),
                readRecipients(request.nodeConfig(), "bcc"),
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
        if (isDocumentResult(deliveryType, result)) {
            return "Word 文档已生成：" + firstNonBlank(stringValue(result.get("fileName")), title);
        }
        return "交付已完成：" + title;
    }

    private boolean isDocumentResult(String deliveryType, Map<String, Object> result) {
        return "document".equals(deliveryType)
            || "word_document".equals(deliveryType)
            || "docx".equals(deliveryType)
            || (result != null && "word_document".equals(stringValue(result.get("adapter"))));
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
            || "document".equals(channel)
            || "word".equals(kind)
            || "word_document".equals(nodeType);
    }

    private SystemCapabilityEntity resolveDeliveryCapability(DeliveryRuntimeRequest request) {
        String capabilityIdText = firstNonBlank(
            stringValue(request.nodeConfig().get("deliveryCapabilityId")),
            stringValue(request.nodeConfig().get("capabilityId"))
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

    /**
     * 判断是否走站内直接交付：显式 direct 模式，或未配置/占位交付能力 ID。
     * 设计器默认「使用交付能力 + none」属于后者，避免演示流程在交付节点必然失败。
     */
    private static boolean shouldUseDirectDelivery(Map<String, Object> nodeConfig) {
        String mode = firstNonBlank(stringValue(nodeConfig.get("deliveryMode")), "direct");
        if ("direct".equals(mode)) {
            return true;
        }
        String capabilityId = firstNonBlank(
            stringValue(nodeConfig.get("deliveryCapabilityId")),
            stringValue(nodeConfig.get("capabilityId"))
        );
        return capabilityId.isBlank() || CAPABILITY_SENTINEL_VALUES.contains(capabilityId.toLowerCase());
    }

    private Map<String, Object> buildPayload(DeliveryRuntimeRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>(request.variables());
        String deliveryTarget = renderString(firstNonBlank(stringValue(request.nodeConfig().get("deliveryTarget")), "请查看上游节点输出。"), request.variables());
        payload.put("deliveryTarget", deliveryTarget);
        payload.put("body", firstNonBlank(stringValue(request.nodeConfig().get("body")), deliveryTarget));
        payload.put("runId", request.run().getId().toString());
        payload.put("nodeRunId", request.nodeRun().getId().toString());
        return payload;
    }

    private String renderString(String value, Map<String, Object> variables) {
        String result = value == null ? "" : value;
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue() == null ? "" : entry.getValue().toString());
        }
        return result;
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

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
