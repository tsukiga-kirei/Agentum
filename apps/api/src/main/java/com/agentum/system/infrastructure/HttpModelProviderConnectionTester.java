package com.agentum.system.infrastructure;

import com.agentum.system.application.ModelProviderConnectionTester;
import com.agentum.system.application.ModelProviderTestOutcome;
import com.agentum.system.application.ModelProviderTestRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class HttpModelProviderConnectionTester implements ModelProviderConnectionTester {

    private static final Logger log = LoggerFactory.getLogger(HttpModelProviderConnectionTester.class);
    private static final int MODEL_PREVIEW_LIMIT = 20;
    private static final int LOG_RESPONSE_BODY_MAX_LENGTH = 4000;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public HttpModelProviderConnectionTester(ObjectMapper objectMapper) {
        this(RestClient.builder()
            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {{
                setConnectTimeout(Duration.ofSeconds(8));
                setReadTimeout(Duration.ofSeconds(20));
            }})
            .build(), objectMapper);
    }

    HttpModelProviderConnectionTester(RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public ModelProviderTestOutcome test(ModelProviderTestRequest request) {
        Instant startedAt = Instant.now();
        URI uri = buildModelListUri(request.baseUrl(), request.modelListEndpoint());
        boolean hasApiKey = request.apiKey() != null && !request.apiKey().isBlank();
        log.debug(
            "模型供应商连接测试请求 providerId={} type={} uri={} hasApiKey={}",
            request.providerId(),
            request.providerType(),
            uri,
            hasApiKey
        );
        try {
            String body = restClient.get()
                .uri(uri)
                .headers(headers -> applyAuthHeaders(headers, request))
                .retrieve()
                .body(String.class);
            log.debug(
                "模型供应商连接测试响应 providerId={} uri={} body={}",
                request.providerId(),
                uri,
                truncateForLog(body, LOG_RESPONSE_BODY_MAX_LENGTH)
            );
            List<String> models = parseModelIds(body);
            long latency = Duration.between(startedAt, Instant.now()).toMillis();
            String summary = models.isEmpty()
                ? "模型供应商连接成功，但未从模型列表响应中解析到模型 ID"
                : "模型供应商连接成功，已解析到 " + models.size() + " 个模型";
            return new ModelProviderTestOutcome("success", summary, models, latency);
        } catch (RestClientResponseException ex) {
            long latency = Duration.between(startedAt, Instant.now()).toMillis();
            log.warn(
                "模型供应商连接测试失败 providerId={} type={} baseUrl={} endpoint={} status={} errorType={}",
                request.providerId(),
                request.providerType(),
                request.baseUrl(),
                request.modelListEndpoint(),
                ex.getStatusCode().value(),
                ex.getClass().getSimpleName()
            );
            log.debug(
                "模型供应商连接测试 HTTP 响应 providerId={} uri={} hasApiKey={} status={} responseBody={} message={}",
                request.providerId(),
                uri,
                hasApiKey,
                ex.getStatusCode().value(),
                truncateForLog(ex.getResponseBodyAsString(), LOG_RESPONSE_BODY_MAX_LENGTH),
                ex.getMessage()
            );
            return new ModelProviderTestOutcome("failed", "模型供应商连接失败，请检查基址 URL、默认模型和 API Key 配置", List.of(), latency);
        } catch (RestClientException ex) {
            long latency = Duration.between(startedAt, Instant.now()).toMillis();
            log.warn(
                "模型供应商连接测试失败 providerId={} type={} baseUrl={} endpoint={} errorType={}",
                request.providerId(),
                request.providerType(),
                request.baseUrl(),
                request.modelListEndpoint(),
                ex.getClass().getSimpleName()
            );
            log.debug(
                "模型供应商连接测试网络异常 providerId={} uri={} hasApiKey={} message={}",
                request.providerId(),
                uri,
                hasApiKey,
                ex.getMessage(),
                ex
            );
            return new ModelProviderTestOutcome("failed", "模型供应商连接失败，请检查基址 URL、默认模型和 API Key 配置", List.of(), latency);
        } catch (IllegalArgumentException ex) {
            long latency = Duration.between(startedAt, Instant.now()).toMillis();
            return new ModelProviderTestOutcome("failed", ex.getMessage(), List.of(), latency);
        }
    }

    private static URI buildModelListUri(String baseUrl, String endpoint) {
        String normalizedBase = baseUrl == null ? "" : baseUrl.trim();
        if (normalizedBase.isEmpty()) {
            throw new IllegalArgumentException("模型供应商基址 URL 未配置");
        }
        String normalizedEndpoint = endpoint == null || endpoint.isBlank() ? "/models" : endpoint.trim();
        if (!normalizedEndpoint.startsWith("/")) {
            normalizedEndpoint = "/" + normalizedEndpoint;
        }
        return URI.create(normalizedBase.replaceAll("/+$", "") + normalizedEndpoint);
    }

    private static void applyAuthHeaders(HttpHeaders headers, ModelProviderTestRequest request) {
        String apiKey = request.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return;
        }
        if ("anthropic-compatible".equals(request.providerType())) {
            headers.set("x-api-key", apiKey);
            headers.set("anthropic-version", "2023-06-01");
            return;
        }
        if ("azure-openai".equals(request.providerType())) {
            headers.set("api-key", apiKey);
            return;
        }
        // OpenAI 兼容与通义兼容模式都使用 Bearer 头，避免把供应商特定 Key 明文放入 URL。
        headers.setBearerAuth(apiKey);
    }

    private List<String> parseModelIds(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root.path("data");
            if (!data.isArray()) {
                data = root.path("models");
            }
            if (!data.isArray()) {
                return List.of();
            }
            List<String> models = new ArrayList<>();
            for (JsonNode item : data) {
                String id = item.path("id").asText("");
                if (id.isBlank()) {
                    id = item.path("name").asText("");
                }
                if (!id.isBlank()) {
                    models.add(id);
                }
                if (models.size() >= MODEL_PREVIEW_LIMIT) {
                    break;
                }
            }
            return models;
        } catch (Exception ex) {
            log.warn("模型供应商列表响应解析失败 errorType={}", ex.getClass().getSimpleName());
            return List.of();
        }
    }

    private static String truncateForLog(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...(truncated)";
    }
}
