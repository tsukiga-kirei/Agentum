package com.agentum.agent.infrastructure;

import com.agentum.agent.application.ModelChatClient;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.RequestIds;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class OpenAiCompatibleModelChatClient implements ModelChatClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleModelChatClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleModelChatClient(ObjectMapper objectMapper) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(8));
        requestFactory.setReadTimeout(Duration.ofSeconds(60));
        this.restClient = RestClient.builder()
            .requestFactory(requestFactory)
            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .build();
        this.objectMapper = objectMapper;
    }

    @Override
    public ChatResult chat(ChatRequest request) {
        if ("anthropic-compatible".equals(request.providerType())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_PROVIDER_TYPE_UNSUPPORTED", "当前运行态暂不支持 Anthropic Messages 协议，请改用 OpenAI 兼容网关");
        }
        Instant startedAt = Instant.now();
        URI uri = buildChatCompletionUri(request);
        Map<String, Object> payload = buildPayload(request);
        try {
            String body = restClient.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> applyAuthHeaders(headers, request))
                .body(objectMapper.writeValueAsString(payload))
                .retrieve()
                .body(String.class);
            long latency = Duration.between(startedAt, Instant.now()).toMillis();
            return parseResult(body, latency);
        } catch (ApiException exception) {
            throw exception;
        } catch (RestClientException exception) {
            log.warn(
                "模型聊天请求失败 providerId={} providerType={} model={} errorType={} requestId={}",
                request.providerId(),
                request.providerType(),
                request.modelName(),
                exception.getClass().getSimpleName(),
                RequestIds.current()
            );
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_CALL_FAILED", "模型调用失败，请检查供应商连通性、模型名称或额度");
        } catch (Exception exception) {
            log.warn(
                "模型聊天响应处理失败 providerId={} providerType={} model={} errorType={} requestId={}",
                request.providerId(),
                request.providerType(),
                request.modelName(),
                exception.getClass().getSimpleName(),
                RequestIds.current()
            );
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_RESPONSE_INVALID", "模型响应无法解析");
        }
    }

    private URI buildChatCompletionUri(ChatRequest request) {
        String baseUrl = request.baseUrl() == null ? "" : request.baseUrl().trim();
        if (baseUrl.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_BASE_URL_REQUIRED", "模型供应商基址 URL 未配置");
        }
        String endpoint = stringOption(request.options(), "chatCompletionEndpoint", "/chat/completions");
        if ("azure-openai".equals(request.providerType())) {
            endpoint = stringOption(request.options(), "chatCompletionEndpoint", "/openai/deployments/" + request.modelName() + "/chat/completions");
            String apiVersion = stringOption(request.options(), "apiVersion", stringOption(request.options(), "api-version", "2024-02-15-preview"));
            endpoint = endpoint + (endpoint.contains("?") ? "&" : "?") + "api-version=" + apiVersion;
        }
        if (!endpoint.startsWith("/")) {
            endpoint = "/" + endpoint;
        }
        return URI.create(baseUrl.replaceAll("/+$", "") + endpoint);
    }

    private Map<String, Object> buildPayload(ChatRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (!"azure-openai".equals(request.providerType())) {
            payload.put("model", request.modelName());
        }
        payload.put("messages", request.messages().stream()
            .map(message -> Map.of("role", message.role(), "content", message.content()))
            .toList());
        if (isReasoningModel(request.modelName())) {
            payload.put("max_completion_tokens", numberOption(request.options(), "maxCompletionTokens", numberOption(request.options(), "maxTokens", 2048)));
        } else {
            payload.put("temperature", decimalOption(request.options(), "temperature", 0.2));
            payload.put("max_tokens", numberOption(request.options(), "maxTokens", 2048));
        }
        return payload;
    }

    private static boolean isReasoningModel(String modelName) {
        String normalized = modelName == null ? "" : modelName.toLowerCase();
        return normalized.startsWith("o1") || normalized.startsWith("o3") || normalized.startsWith("o4") || normalized.startsWith("gpt-5");
    }

    private static void applyAuthHeaders(HttpHeaders headers, ChatRequest request) {
        String apiKey = request.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return;
        }
        if ("azure-openai".equals(request.providerType())) {
            headers.set("api-key", apiKey);
            return;
        }
        headers.setBearerAuth(apiKey);
    }

    private ChatResult parseResult(String body, long latencyMs) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        JsonNode firstChoice = root.path("choices").isArray() && !root.path("choices").isEmpty()
            ? root.path("choices").get(0)
            : objectMapper.createObjectNode();
        String content = firstChoice.path("message").path("content").asText("");
        if (content.isBlank()) {
            JsonNode contentArray = firstChoice.path("message").path("content");
            if (contentArray.isArray()) {
                List<String> parts = new ArrayList<>();
                for (JsonNode part : contentArray) {
                    String text = part.path("text").asText("");
                    if (!text.isBlank()) {
                        parts.add(text);
                    }
                }
                content = String.join("\n", parts);
            }
        }
        if (content.isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_RESPONSE_EMPTY", "模型未返回可用文本");
        }
        Map<String, Object> usage = objectMapper.convertValue(root.path("usage"), new TypeReference<Map<String, Object>>() {
        });
        Map<String, Object> responseSnapshot = new LinkedHashMap<>();
        responseSnapshot.put("content", content);
        responseSnapshot.put("finishReason", firstChoice.path("finish_reason").asText(""));
        responseSnapshot.put("id", root.path("id").asText(""));
        return new ChatResult(content, responseSnapshot, usage, latencyMs);
    }

    private static String stringOption(Map<String, Object> options, String key, String fallback) {
        Object value = options.get(key);
        String text = value == null ? "" : value.toString().trim();
        return text.isBlank() ? fallback : text;
    }

    private static int numberOption(Map<String, Object> options, String key, int fallback) {
        Object value = options.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(value.toString());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static double decimalOption(Map<String, Object> options, String key, double fallback) {
        Object value = options.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? fallback : Double.parseDouble(value.toString());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}
