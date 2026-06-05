package com.agentum.mcp.infrastructure;

import com.agentum.mcp.application.McpRuntimeClient;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.RequestIds;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class HttpMcpSseRuntimeClient implements McpRuntimeClient {

    private static final Logger log = LoggerFactory.getLogger(HttpMcpSseRuntimeClient.class);
    private static final String MCP_PROTOCOL_VERSION = "2024-11-05";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration OPERATION_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public HttpMcpSseRuntimeClient(ObjectMapper objectMapper) {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolResult callTool(ToolCall call) {
        String sseUrl = call.sseUrl() == null ? "" : call.sseUrl().trim();
        if (sseUrl.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MCP_SSE_URL_REQUIRED", "MCP 能力未配置 SSE 地址");
        }
        if (call.toolName() == null || call.toolName().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MCP_TOOL_NAME_REQUIRED", "MCP 节点未配置工具名称");
        }

        Instant startedAt = Instant.now();
        BlockingQueue<SseEvent> events = new LinkedBlockingQueue<>();
        AtomicReference<String> messageEndpoint = new AtomicReference<>();
        AtomicBoolean sseClosed = new AtomicBoolean(false);
        Thread sseReader = new Thread(() -> readSseStream(sseUrl, events, messageEndpoint, sseClosed), "mcp-runtime-sse-reader");
        sseReader.setDaemon(true);
        sseReader.start();

        try {
            String endpointPath = waitForEndpoint(messageEndpoint, events, OPERATION_TIMEOUT);
            URI messageUri = resolveMessageUri(sseUrl, endpointPath);
            postJsonRpc(messageUri, initializeRequest(1));
            waitForJsonRpcResult(events, 1, OPERATION_TIMEOUT);
            postJsonRpc(messageUri, initializedNotification());
            postJsonRpc(messageUri, toolsCallRequest(2, call.toolName(), call.arguments()));
            JsonNode result = waitForJsonRpcResult(events, 2, OPERATION_TIMEOUT);
            long latency = Duration.between(startedAt, Instant.now()).toMillis();
            return new ToolResult(parseToolResult(result), latency);
        } catch (ApiException exception) {
            throw exception;
        } catch (McpRuntimeException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MCP_CALL_FAILED", exception.getMessage());
        } catch (Exception exception) {
            log.warn(
                "MCP SSE 工具调用异常 capabilityId={} toolName={} errorType={} requestId={}",
                call.capabilityId(),
                call.toolName(),
                exception.getClass().getSimpleName(),
                RequestIds.current()
            );
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MCP_CALL_FAILED", "MCP 工具调用失败，请检查服务连通性");
        } finally {
            sseClosed.set(true);
            sseReader.interrupt();
        }
    }

    private void readSseStream(
        String sseUrl,
        BlockingQueue<SseEvent> events,
        AtomicReference<String> messageEndpoint,
        AtomicBoolean sseClosed
    ) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(sseUrl))
            .timeout(OPERATION_TIMEOUT.plusSeconds(2))
            .header("Accept", "text/event-stream")
            .GET()
            .build();
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                events.offer(SseEvent.failure("MCP SSE 地址返回非成功状态：" + response.statusCode()));
                return;
            }
            try (InputStream body = response.body();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                String eventType = null;
                StringBuilder data = new StringBuilder();
                String line;
                while (!sseClosed.get() && (line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        emitEvent(eventType, data.toString(), events, messageEndpoint);
                        eventType = null;
                        data.setLength(0);
                        continue;
                    }
                    if (line.startsWith("event:")) {
                        eventType = line.substring("event:".length()).trim();
                    } else if (line.startsWith("data:")) {
                        if (data.length() > 0) {
                            data.append('\n');
                        }
                        data.append(line.substring("data:".length()).trim());
                    }
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            events.offer(SseEvent.failure("MCP SSE 连接失败：" + exception.getMessage()));
        }
    }

    private static void emitEvent(
        String eventType,
        String data,
        BlockingQueue<SseEvent> events,
        AtomicReference<String> messageEndpoint
    ) {
        if (eventType == null || data.isBlank()) {
            return;
        }
        if ("endpoint".equals(eventType)) {
            messageEndpoint.compareAndSet(null, data.trim());
        }
        events.offer(new SseEvent(eventType, data.trim(), null));
    }

    private String waitForEndpoint(
        AtomicReference<String> messageEndpoint,
        BlockingQueue<SseEvent> events,
        Duration timeout
    ) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            String endpoint = messageEndpoint.get();
            if (endpoint != null && !endpoint.isBlank()) {
                return endpoint;
            }
            SseEvent event = events.poll(200, TimeUnit.MILLISECONDS);
            if (event != null && event.failureMessage() != null) {
                throw new McpRuntimeException(event.failureMessage());
            }
        }
        throw new McpRuntimeException("MCP SSE 握手超时，未收到 message 端点");
    }

    private JsonNode waitForJsonRpcResult(BlockingQueue<SseEvent> events, int expectedId, Duration timeout)
        throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            SseEvent event = events.poll(200, TimeUnit.MILLISECONDS);
            if (event == null) {
                continue;
            }
            if (event.failureMessage() != null) {
                throw new McpRuntimeException(event.failureMessage());
            }
            if (!"message".equals(event.eventType())) {
                continue;
            }
            JsonNode payload;
            try {
                payload = objectMapper.readTree(event.data());
            } catch (Exception exception) {
                throw new McpRuntimeException("MCP SSE 消息解析失败");
            }
            if (payload.path("id").asInt(-1) != expectedId) {
                continue;
            }
            if (payload.hasNonNull("error")) {
                throw new McpRuntimeException("MCP 协议调用失败：" + payload.path("error").path("message").asText("未知错误"));
            }
            return payload.path("result");
        }
        throw new McpRuntimeException("MCP 协议响应超时，未收到 id=" + expectedId + " 的 JSON-RPC 结果");
    }

    private void postJsonRpc(URI messageUri, ObjectNode payload) throws Exception {
        String body = objectMapper.writeValueAsString(payload);
        HttpRequest request = HttpRequest.newBuilder(messageUri)
            .timeout(OPERATION_TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new McpRuntimeException("MCP message 端点返回非成功状态：" + response.statusCode());
        }
    }

    private static URI resolveMessageUri(String sseUrl, String endpointPath) {
        URI endpoint = URI.create(endpointPath);
        if (endpoint.isAbsolute()) {
            return endpoint;
        }
        URI sse = URI.create(sseUrl);
        return URI.create(sse.getScheme() + "://" + sse.getAuthority() + endpointPath);
    }

    private ObjectNode initializeRequest(int id) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("protocolVersion", MCP_PROTOCOL_VERSION);
        params.set("capabilities", objectMapper.createObjectNode());
        ObjectNode clientInfo = objectMapper.createObjectNode();
        clientInfo.put("name", "agentum-runtime");
        clientInfo.put("version", "0.1.0");
        params.set("clientInfo", clientInfo);
        return jsonRpcRequest(id, "initialize", params);
    }

    private ObjectNode initializedNotification() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("jsonrpc", "2.0");
        payload.put("method", "notifications/initialized");
        payload.set("params", objectMapper.createObjectNode());
        return payload;
    }

    private ObjectNode toolsCallRequest(int id, String toolName, Map<String, Object> arguments) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", objectMapper.valueToTree(arguments));
        return jsonRpcRequest(id, "tools/call", params);
    }

    private ObjectNode jsonRpcRequest(int id, String method, JsonNode params) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("jsonrpc", "2.0");
        payload.put("id", id);
        payload.put("method", method);
        payload.set("params", params);
        return payload;
    }

    private Map<String, Object> parseToolResult(JsonNode result) {
        Map<String, Object> response = objectMapper.convertValue(result, new TypeReference<LinkedHashMap<String, Object>>() {
        });
        JsonNode content = result.path("content");
        if (content.isArray()) {
            List<String> textParts = new ArrayList<>();
            for (JsonNode item : content) {
                String text = item.path("text").asText("");
                if (!text.isBlank()) {
                    textParts.add(text);
                }
            }
            if (!textParts.isEmpty()) {
                response.put("text", String.join("\n", textParts));
            }
        }
        return response;
    }

    private record SseEvent(String eventType, String data, String failureMessage) {
        static SseEvent failure(String message) {
            return new SseEvent("failure", "", message);
        }
    }

    private static final class McpRuntimeException extends RuntimeException {
        private McpRuntimeException(String message) {
            super(message);
        }
    }
}
