package com.agentum.agent.infrastructure;

import com.agentum.agent.application.FinalAnswerContentResolver;
import com.agentum.agent.application.ModelChatClient;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.RequestIds;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class OpenAiCompatibleModelChatClient implements ModelChatClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleModelChatClient.class);
    private static final int LOG_TEXT_MAX_LENGTH = 400;
    private static final int LOG_RESPONSE_BODY_MAX_LENGTH = 4000;
    private static final long STREAM_CANCEL_POLL_INTERVAL_MS = 300L;

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
        logChatRequest(request, uri, payload, false);
        try {
            String body = restClient.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> applyAuthHeaders(headers, request))
                .body(objectMapper.writeValueAsString(payload))
                .retrieve()
                .body(String.class);
            long latency = Duration.between(startedAt, Instant.now()).toMillis();
            ChatResult result = parseResult(body, latency);
            String resolvedAnswer = FinalAnswerContentResolver.resolve(result, "", objectMapper);
            logChatResponse(request, result, resolvedAnswer, latency, false);
            return result;
        } catch (ApiException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            log.warn(
                "模型聊天 HTTP 错误 providerId={} providerType={} model={} status={} body={} requestId={}",
                request.providerId(),
                request.providerType(),
                request.modelName(),
                exception.getStatusCode().value(),
                truncateForLog(exception.getResponseBodyAsString(), LOG_RESPONSE_BODY_MAX_LENGTH),
                RequestIds.current()
            );
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_CALL_FAILED", "模型调用失败，请检查供应商连通性、模型名称或额度");
        } catch (RestClientException exception) {
            log.warn(
                "模型聊天请求失败 providerId={} providerType={} model={} errorType={} message={} requestId={}",
                request.providerId(),
                request.providerType(),
                request.modelName(),
                exception.getClass().getSimpleName(),
                exception.getMessage(),
                RequestIds.current()
            );
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_CALL_FAILED", "模型调用失败，请检查供应商连通性、模型名称或额度");
        } catch (Exception exception) {
            log.warn(
                "模型聊天响应处理失败 providerId={} providerType={} model={} errorType={} message={} requestId={}",
                request.providerId(),
                request.providerType(),
                request.modelName(),
                exception.getClass().getSimpleName(),
                exception.getMessage(),
                RequestIds.current(),
                exception
            );
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_RESPONSE_INVALID", "模型响应无法解析");
        }
    }

    @Override
    public void chatStream(ChatRequest request, StreamingCallback callback) {
        if ("anthropic-compatible".equals(request.providerType())) {
            callback.onError("MODEL_PROVIDER_TYPE_UNSUPPORTED", "当前运行态暂不支持 Anthropic Messages 协议，请改用 OpenAI 兼容网关");
            return;
        }
        Instant startedAt = Instant.now();
        URI uri = buildChatCompletionUri(request);
        Map<String, Object> payload = buildPayload(request);
        Map<String, Object> streamPayload = new LinkedHashMap<>(payload);
        streamPayload.put("stream", true);
        logChatRequest(request, uri, streamPayload, true);

        try {
            String requestBodyJson = objectMapper.writeValueAsString(streamPayload);
            restClient.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> applyAuthHeaders(headers, request))
                .body(requestBodyJson)
                .exchange((clientRequest, response) -> {
                    if (response.getStatusCode().isError()) {
                        byte[] bodyBytes = response.getBody().readAllBytes();
                        String errorBody = new String(bodyBytes, java.nio.charset.StandardCharsets.UTF_8);
                        log.warn(
                            "模型聊天流 HTTP 错误 providerId={} model={} status={} body={} requestId={}",
                            request.providerId(),
                            request.modelName(),
                            response.getStatusCode().value(),
                            truncateForLog(errorBody, LOG_RESPONSE_BODY_MAX_LENGTH),
                            RequestIds.current()
                        );
                        callback.onError("MODEL_HTTP_ERROR", "HTTP " + response.getStatusCode() + ": " + errorBody);
                        return null;
                    }
                    InputStream rawBody = response.getBody();
                    AtomicBoolean abortedByCancel = new AtomicBoolean(false);
                    Thread cancelWatcher = startStreamCancelWatcher(request.cancelProbe(), rawBody, abortedByCancel);
                    try (java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(rawBody, java.nio.charset.StandardCharsets.UTF_8))) {
                        String line;
                        StringBuilder accumulated = new StringBuilder();
                        String responseId = "";
                        String finishReason = "";
                        Map<String, Object> usage = new LinkedHashMap<>();
                        OpenAiStreamSupport.StreamingToolCallAssembler toolCallAssembler =
                            new OpenAiStreamSupport.StreamingToolCallAssembler();
                        OpenAiStreamSupport.FinalAnswerArgumentStreamer finalAnswerStreamer =
                            new OpenAiStreamSupport.FinalAnswerArgumentStreamer();
                        int previousFinalAnswerArgumentsLength = 0;

                        while ((line = reader.readLine()) != null) {
                            if (isStreamCancelled(request.cancelProbe(), abortedByCancel)) {
                                closeQuietly(rawBody);
                                break;
                            }
                            String trimmed = line.trim();
                            if (trimmed.isEmpty()) {
                                continue;
                            }
                            if (trimmed.startsWith("data:")) {
                                String data = trimmed.substring(5).trim();
                                if ("[DONE]".equals(data)) {
                                    break;
                                }
                                try {
                                    JsonNode node = objectMapper.readTree(data);
                                    if (node.has("id")) {
                                        responseId = node.get("id").asText("");
                                    }
                                    JsonNode choices = node.path("choices");
                                    if (choices.isArray() && !choices.isEmpty()) {
                                        JsonNode firstChoice = choices.get(0);
                                        if (firstChoice.has("finish_reason") && !firstChoice.get("finish_reason").isNull()) {
                                            finishReason = firstChoice.get("finish_reason").asText("");
                                        }
                                        JsonNode delta = firstChoice.path("delta");
                                        if (delta.has("content") && !delta.get("content").isNull()) {
                                            String content = delta.get("content").asText("");
                                            if (!content.isEmpty()) {
                                                accumulated.append(content);
                                                callback.onChunk(content);
                                            }
                                        }
                                        if (delta.has("tool_calls")) {
                                            toolCallAssembler.absorb(delta.get("tool_calls"));
                                            String currentArguments = toolCallAssembler.latestFinalAnswerArguments();
                                            if (currentArguments.length() > previousFinalAnswerArgumentsLength) {
                                                String argumentDelta = currentArguments.substring(previousFinalAnswerArgumentsLength);
                                                previousFinalAnswerArgumentsLength = currentArguments.length();
                                                String answerDelta = finalAnswerStreamer.consume(argumentDelta);
                                                if (!answerDelta.isEmpty()) {
                                                    callback.onFinalAnswerDelta(answerDelta, finalAnswerStreamer.accumulatedAnswer());
                                                }
                                            }
                                        }
                                    }
                                    if (node.has("usage") && !node.get("usage").isNull()) {
                                        usage = objectMapper.convertValue(node.get("usage"), new TypeReference<Map<String, Object>>() {});
                                    }
                                } catch (Exception e) {
                                    log.debug("解析流式 chunk 失败: {}", trimmed, e);
                                }
                            }
                        }
                        if (isStreamCancelled(request.cancelProbe(), abortedByCancel)) {
                            logStreamAbortedByCancel(request, startedAt);
                            callback.onError("RUN_CANCELLED", "任务已中断");
                            return null;
                        }
                        // 部分供应商在最后一个 chunk 才下发完整 tool_calls，此处补推一次累积 answer，避免运行态丢失流式正文。
                        String flushedFinalAnswer = finalAnswerStreamer.accumulatedAnswer();
                        if (!flushedFinalAnswer.isEmpty()) {
                            callback.onFinalAnswerDelta("", flushedFinalAnswer);
                        }
                        long latency = Duration.between(startedAt, Instant.now()).toMillis();
                        List<ModelChatClient.ToolCall> toolCalls = toolCallAssembler.toToolCalls();
                        Map<String, Object> responseSnapshot = OpenAiStreamSupport.buildResponseSnapshot(
                            accumulated.toString(),
                            finishReason,
                            responseId,
                            toolCalls
                        );

                        ChatResult result = new ChatResult(
                            accumulated.toString(),
                            responseSnapshot,
                            usage,
                            latency,
                            toolCalls,
                            finishReason
                        );
                        String resolvedAnswer = FinalAnswerContentResolver.resolve(
                            result,
                            finalAnswerStreamer.accumulatedAnswer(),
                            objectMapper
                        );
                        logChatResponse(request, result, resolvedAnswer, latency, true);
                        callback.onComplete(result);
                    } catch (IOException exception) {
                        if (isStreamCancelled(request.cancelProbe(), abortedByCancel)) {
                            logStreamAbortedByCancel(request, startedAt);
                            callback.onError("RUN_CANCELLED", "任务已中断");
                            return null;
                        }
                        log.warn("处理模型聊天流 IO 异常 providerId={} model={} requestId={}",
                            request.providerId(), request.modelName(), RequestIds.current(), exception);
                        callback.onError("MODEL_STREAM_PROCESSING_ERROR", exception.getMessage());
                    } catch (Exception e) {
                        if (isStreamCancelled(request.cancelProbe(), abortedByCancel)) {
                            logStreamAbortedByCancel(request, startedAt);
                            callback.onError("RUN_CANCELLED", "任务已中断");
                            return null;
                        }
                        log.warn("处理模型聊天流异常", e);
                        callback.onError("MODEL_STREAM_PROCESSING_ERROR", e.getMessage());
                    } finally {
                        stopStreamCancelWatcher(cancelWatcher);
                    }
                    return null;
                });
        } catch (ApiException exception) {
            callback.onError(exception.getCode(), exception.getMessage());
        } catch (RestClientException exception) {
            log.warn(
                "模型聊天请求流失败 providerId={} providerType={} model={} errorType={} requestId={}",
                request.providerId(),
                request.providerType(),
                request.modelName(),
                exception.getClass().getSimpleName(),
                RequestIds.current()
            );
            callback.onError("MODEL_CALL_FAILED", "模型流式调用失败，请检查供应商连通性、模型名称或额度");
        } catch (Exception exception) {
            log.warn(
                "模型聊天响应流处理失败 providerId={} providerType={} model={} errorType={} requestId={}",
                request.providerId(),
                request.providerType(),
                request.modelName(),
                exception.getClass().getSimpleName(),
                RequestIds.current()
            );
            callback.onError("MODEL_RESPONSE_INVALID", "模型流式响应无法解析");
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
            .map(this::messagePayload)
            .toList());
        if (!request.tools().isEmpty()) {
            payload.put("tools", request.tools().stream()
                .map(tool -> Map.of(
                    "type", "function",
                    "function", Map.of(
                        "name", tool.name(),
                        "description", tool.description(),
                        "parameters", tool.parameters()
                    )
                ))
                .toList());
            payload.put("tool_choice", stringOption(request.options(), "toolChoice", "auto"));
            payload.put("parallel_tool_calls", booleanOption(request.options(), "parallelToolCalls", false));
        }
        if (isReasoningModel(request.modelName())) {
            Integer maxCompletionTokens = optionalIntegerOption(request.options(), "maxCompletionTokens");
            if (maxCompletionTokens == null) {
                maxCompletionTokens = optionalIntegerOption(request.options(), "maxTokens");
            }
            if (maxCompletionTokens != null) {
                payload.put("max_completion_tokens", maxCompletionTokens);
            }
        } else {
            payload.put("temperature", decimalOption(request.options(), "temperature", 0.2));
            Integer maxTokens = optionalIntegerOption(request.options(), "maxTokens");
            if (maxTokens != null) {
                payload.put("max_tokens", maxTokens);
            }
        }
        mergeProviderExtensions(payload, request.options());
        return payload;
    }

    /**
     * 允许在模型供应商 settings 中透传网关特有字段，例如 GLM 的 chat_template_kwargs。
     */
    private void mergeProviderExtensions(Map<String, Object> payload, Map<String, Object> options) {
        if (options == null || options.isEmpty()) {
            return;
        }
        for (String key : List.of("chat_template_kwargs", "top_p", "frequency_penalty", "presence_penalty")) {
            Object value = options.get(key);
            if (value != null) {
                payload.put(key, value);
            }
        }
        Object extraBody = options.get("extraRequestBody");
        if (extraBody instanceof Map<?, ?> extra) {
            extra.forEach((key, value) -> {
                if (key != null && value != null && !payload.containsKey(key.toString())) {
                    payload.put(key.toString(), value);
                }
            });
        }
    }

    private Map<String, Object> messagePayload(ModelChatClient.ChatMessage message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("role", message.role());
        if ("tool".equals(message.role())) {
            payload.put("tool_call_id", message.toolCallId());
            payload.put("content", message.content());
            return payload;
        }
        if ("assistant".equals(message.role()) && !message.toolCalls().isEmpty()) {
            payload.put("content", message.content().isBlank() ? null : message.content());
            payload.put("tool_calls", message.toolCalls().stream()
                .map(toolCall -> Map.of(
                    "id", toolCall.id(),
                    "type", "function",
                    "function", Map.of(
                        "name", toolCall.name(),
                        "arguments", toolCall.argumentsJson()
                    )
                ))
                .toList());
            return payload;
        }
        payload.put("content", message.content());
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
        if (body == null || body.isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_RESPONSE_EMPTY", "模型返回空响应体");
        }
        JsonNode root = objectMapper.readTree(body);
        if (root.has("error")) {
            String message = root.path("error").path("message").asText("模型网关返回错误");
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_CALL_FAILED", message);
        }
        JsonNode firstChoice = root.path("choices").isArray() && !root.path("choices").isEmpty()
            ? root.path("choices").get(0)
            : objectMapper.createObjectNode();
        JsonNode message = firstChoice.path("message");
        String content = extractMessageContent(message);
        List<ModelChatClient.ToolCall> toolCalls = parseToolCalls(message.path("tool_calls"));
        if (content.isBlank() && toolCalls.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_RESPONSE_EMPTY", "模型未返回可用文本或工具调用");
        }
        Map<String, Object> usage = parseUsage(root.path("usage"));
        String finishReason = firstChoice.path("finish_reason").asText("");
        Map<String, Object> responseSnapshot = new LinkedHashMap<>();
        responseSnapshot.put("content", content);
        responseSnapshot.put("finishReason", finishReason);
        responseSnapshot.put("id", root.path("id").asText(""));
        if (!toolCalls.isEmpty()) {
            responseSnapshot.put("toolCalls", toolCalls.stream()
                .map(toolCall -> Map.of(
                    "id", toolCall.id(),
                    "name", toolCall.name(),
                    "arguments", toolCall.argumentsJson()
                ))
                .toList());
        }
        return new ChatResult(content, responseSnapshot, usage, latencyMs, toolCalls, finishReason);
    }

    private String extractMessageContent(JsonNode message) {
        JsonNode contentNode = message.path("content");
        if (contentNode.isNull() || contentNode.isMissingNode()) {
            return "";
        }
        if (contentNode.isTextual()) {
            return contentNode.asText("");
        }
        if (contentNode.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode part : contentNode) {
                String text = part.path("text").asText("");
                if (!text.isBlank()) {
                    parts.add(text);
                }
            }
            return String.join("\n", parts);
        }
        return contentNode.asText("");
    }

    private Map<String, Object> parseUsage(JsonNode usageNode) {
        if (usageNode == null || usageNode.isNull() || usageNode.isMissingNode()) {
            return Map.of();
        }
        Map<String, Object> usage = objectMapper.convertValue(usageNode, new TypeReference<Map<String, Object>>() {});
        return usage == null ? Map.of() : usage;
    }

    private List<ModelChatClient.ToolCall> parseToolCalls(JsonNode toolCallsNode) {
        if (!toolCallsNode.isArray()) {
            return List.of();
        }
        List<ModelChatClient.ToolCall> toolCalls = new ArrayList<>();
        for (JsonNode item : toolCallsNode) {
            JsonNode function = item.path("function");
            String name = function.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            toolCalls.add(new ModelChatClient.ToolCall(
                item.path("id").asText(""),
                name,
                readArgumentsJson(function.path("arguments"))
            ));
        }
        return toolCalls;
    }

    private String readArgumentsJson(JsonNode argumentsNode) {
        if (argumentsNode == null || argumentsNode.isNull() || argumentsNode.isMissingNode()) {
            return "{}";
        }
        if (argumentsNode.isTextual()) {
            String text = argumentsNode.asText("");
            return text.isBlank() ? "{}" : text;
        }
        return argumentsNode.toString();
    }

    private static String stringOption(Map<String, Object> options, String key, String fallback) {
        Object value = options.get(key);
        String text = value == null ? "" : value.toString().trim();
        return text.isBlank() ? fallback : text;
    }

    private static Integer optionalIntegerOption(Map<String, Object> options, String key) {
        if (options == null || !options.containsKey(key)) {
            return null;
        }
        Object value = options.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? null : Integer.parseInt(value.toString());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static int numberOption(Map<String, Object> options, String key, int fallback) {
        Integer parsed = optionalIntegerOption(options, key);
        return parsed == null ? fallback : parsed;
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

    private static boolean booleanOption(Map<String, Object> options, String key, boolean fallback) {
        Object value = options.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
    }

    /**
     * 记录模型对接请求摘要，便于排查网关地址、模型名、工具与消息结构；不输出 API Key。
     */
    private void logChatRequest(ChatRequest request, URI uri, Map<String, Object> payload, boolean streaming) {
        List<String> toolNames = request.tools().stream()
            .map(ModelChatClient.ToolDefinition::name)
            .toList();
        List<String> messageRoles = request.messages().stream()
            .map(ModelChatClient.ChatMessage::role)
            .toList();
        log.debug(
            "模型聊天请求 providerId={} providerType={} model={} uri={} streaming={} messageCount={} messageRoles={} toolCount={} toolNames={} requestPayload={} requestId={}",
            request.providerId(),
            request.providerType(),
            request.modelName(),
            uri,
            streaming,
            request.messages().size(),
            messageRoles,
            toolNames.size(),
            toolNames,
            payloadSummaryForLog(payload),
            RequestIds.current()
        );
    }

    /**
     * 记录模型对接响应摘要。contentPreview/responseBody 使用与落库、前端一致的 resolvedFinalAnswer，
     * 避免 content 与 final_answer 工具参数不一致时日志误导排查。
     */
    private void logChatResponse(ChatRequest request, ChatResult result, String resolvedFinalAnswer, long latencyMs, boolean streaming) {
        List<String> toolCallNames = result.toolCalls().stream()
            .map(ModelChatClient.ToolCall::name)
            .collect(Collectors.toList());
        String loggedAnswer = resolvedFinalAnswer == null ? "" : resolvedFinalAnswer;
        log.debug(
            "模型聊天响应 providerId={} providerType={} model={} streaming={} latencyMs={} finishReason={} contentLength={} toolCallCount={} toolCallNames={} tokenUsage={} contentPreview={} responseBody={} requestId={}",
            request.providerId(),
            request.providerType(),
            request.modelName(),
            streaming,
            latencyMs,
            result.finishReason(),
            loggedAnswer.length(),
            result.toolCalls().size(),
            toolCallNames,
            result.tokenUsage(),
            truncateForLog(loggedAnswer, LOG_TEXT_MAX_LENGTH),
            truncateForLog(loggedAnswer, LOG_RESPONSE_BODY_MAX_LENGTH),
            RequestIds.current()
        );
    }

    private String payloadSummaryForLog(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(sanitizePayloadForLog(payload));
        } catch (Exception exception) {
            return "[请求体序列化失败]";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sanitizePayloadForLog(Map<String, Object> payload) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        payload.forEach((key, value) -> {
            if ("messages".equals(key) && value instanceof List<?> messages) {
                sanitized.put(key, messages.stream()
                    .map(this::sanitizeMessageForLog)
                    .toList());
                return;
            }
            if ("tools".equals(key) && value instanceof List<?> tools) {
                sanitized.put(key, tools.stream()
                    .map(item -> item instanceof Map<?, ?> map ? sanitizeToolForLog((Map<String, Object>) map) : item)
                    .toList());
                return;
            }
            sanitized.put(key, value);
        });
        return sanitized;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sanitizeMessageForLog(Object message) {
        if (!(message instanceof Map<?, ?> raw)) {
            return Map.of("value", truncateForLog(String.valueOf(message), LOG_TEXT_MAX_LENGTH));
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            String field = key == null ? "" : key.toString();
            if ("content".equals(field) && value != null) {
                sanitized.put(field, truncateForLog(String.valueOf(value), LOG_TEXT_MAX_LENGTH));
                return;
            }
            if ("tool_calls".equals(field) && value instanceof List<?> toolCalls) {
                sanitized.put(field, toolCalls.stream()
                    .map(item -> item instanceof Map<?, ?> map ? sanitizeToolCallForLog((Map<String, Object>) map) : item)
                    .toList());
                return;
            }
            sanitized.put(field, value);
        });
        return sanitized;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sanitizeToolForLog(Map<String, Object> tool) {
        Map<String, Object> sanitized = new LinkedHashMap<>(tool);
        Object function = sanitized.get("function");
        if (function instanceof Map<?, ?> functionMap) {
            Map<String, Object> sanitizedFunction = new LinkedHashMap<>();
            functionMap.forEach((key, value) -> {
                String field = key == null ? "" : key.toString();
                if ("parameters".equals(field) && value != null) {
                    sanitizedFunction.put(field, truncateForLog(String.valueOf(value), LOG_TEXT_MAX_LENGTH));
                    return;
                }
                sanitizedFunction.put(field, value);
            });
            sanitized.put("function", sanitizedFunction);
        }
        return sanitized;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sanitizeToolCallForLog(Map<String, Object> toolCall) {
        Map<String, Object> sanitized = new LinkedHashMap<>(toolCall);
        Object function = sanitized.get("function");
        if (function instanceof Map<?, ?> functionMap) {
            Map<String, Object> sanitizedFunction = new LinkedHashMap<>();
            functionMap.forEach((key, value) -> {
                String field = key == null ? "" : key.toString();
                if ("arguments".equals(field) && value != null) {
                    sanitizedFunction.put(field, truncateForLog(String.valueOf(value), LOG_TEXT_MAX_LENGTH));
                    return;
                }
                sanitizedFunction.put(field, value);
            });
            sanitized.put("function", sanitizedFunction);
        }
        return sanitized;
    }

    private static String truncateForLog(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...(truncated," + value.length() + " chars)";
    }

    /**
     * 后台轮询中断信号：readLine 阻塞期间无法检查 cancel，需主动关闭响应体打断 HTTP 流。
     */
    private static Thread startStreamCancelWatcher(
        BooleanSupplier cancelProbe,
        InputStream rawBody,
        AtomicBoolean abortedByCancel
    ) {
        if (cancelProbe == null) {
            return null;
        }
        Thread watcher = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(STREAM_CANCEL_POLL_INTERVAL_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (cancelProbe.getAsBoolean()) {
                    abortedByCancel.set(true);
                    closeQuietly(rawBody);
                    return;
                }
            }
        }, "model-stream-cancel-watch");
        watcher.setDaemon(true);
        watcher.start();
        return watcher;
    }

    private static void stopStreamCancelWatcher(Thread cancelWatcher) {
        if (cancelWatcher == null) {
            return;
        }
        cancelWatcher.interrupt();
    }

    private static boolean isStreamCancelled(BooleanSupplier cancelProbe, AtomicBoolean abortedByCancel) {
        return abortedByCancel.get() || (cancelProbe != null && cancelProbe.getAsBoolean());
    }

    private static void closeQuietly(InputStream rawBody) {
        if (rawBody == null) {
            return;
        }
        try {
            rawBody.close();
        } catch (IOException ignored) {
            // 关闭流以打断阻塞读即可，无需向上抛出。
        }
    }

    private void logStreamAbortedByCancel(ChatRequest request, Instant startedAt) {
        long latencyMs = Duration.between(startedAt, Instant.now()).toMillis();
        log.info(
            "模型流式调用因任务中断而终止 providerId={} model={} latencyMs={} requestId={}",
            request.providerId(),
            request.modelName(),
            latencyMs,
            RequestIds.current()
        );
    }
}
