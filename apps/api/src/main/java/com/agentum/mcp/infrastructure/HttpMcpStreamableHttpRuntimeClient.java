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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component("mcpStreamableHttpRuntimeClient")
public class HttpMcpStreamableHttpRuntimeClient implements McpRuntimeClient {

    private static final Logger log = LoggerFactory.getLogger(HttpMcpStreamableHttpRuntimeClient.class);
    private static final String MCP_PROTOCOL_VERSION = "2024-11-05";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration OPERATION_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public HttpMcpStreamableHttpRuntimeClient(ObjectMapper objectMapper) {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolResult callTool(ToolCall call) {
        String endpointUrl = call.endpointUrl() == null ? "" : call.endpointUrl().trim();
        if (endpointUrl.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MCP_ENDPOINT_URL_REQUIRED", "MCP 能力未配置 HTTP 端点");
        }
        if (call.toolName() == null || call.toolName().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MCP_TOOL_NAME_REQUIRED", "MCP 节点未配置工具名称");
        }

        Instant startedAt = Instant.now();
        try {
            URI endpointUri = URI.create(endpointUrl);
            // 1. initialize
            postJsonRpcAndGetResult(endpointUri, initializeRequest(1), 1);
            // 2. notifications/initialized
            postNotification(endpointUri, initializedNotification());
            // 3. tools/call
            JsonNode result = postJsonRpcAndGetResult(endpointUri, toolsCallRequest(2, call.toolName(), call.arguments()), 2);
            long latency = Duration.between(startedAt, Instant.now()).toMillis();
            return new ToolResult(parseToolResult(result), latency);
        } catch (ApiException exception) {
            throw exception;
        } catch (McpRuntimeException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MCP_CALL_FAILED", exception.getMessage());
        } catch (Exception exception) {
            log.warn(
                "MCP Streamable HTTP 工具调用异常 capabilityId={} toolName={} errorType={} requestId={}",
                call.capabilityId(),
                call.toolName(),
                exception.getClass().getSimpleName(),
                RequestIds.current()
            );
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MCP_CALL_FAILED", "MCP 工具调用失败，请检查服务连通性");
        }
    }

    private JsonNode postJsonRpcAndGetResult(URI endpointUri, ObjectNode payload, int expectedId) throws Exception {
        String body = objectMapper.writeValueAsString(payload);
        HttpRequest request = HttpRequest.newBuilder(endpointUri)
            .timeout(OPERATION_TIMEOUT)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new McpRuntimeException("MCP HTTP 接口返回错误状态码：" + response.statusCode());
        }

        String contentType = response.headers().firstValue("Content-Type").orElse("application/json").toLowerCase();
        if (contentType.contains("text/event-stream")) {
            return parseSseResponse(response.body(), expectedId);
        } else {
            try (InputStream is = response.body()) {
                JsonNode root = objectMapper.readTree(is);
                if (root.path("id").asInt(-1) != expectedId) {
                    throw new McpRuntimeException("MCP HTTP 响应 ID 不匹配：" + root.path("id").asInt(-1));
                }
                if (root.hasNonNull("error")) {
                    throw new McpRuntimeException("MCP 协议调用失败：" + root.path("error").path("message").asText("未知错误"));
                }
                return root.path("result");
            }
        }
    }

    private void postNotification(URI endpointUri, ObjectNode payload) throws Exception {
        String body = objectMapper.writeValueAsString(payload);
        HttpRequest request = HttpRequest.newBuilder(endpointUri)
            .timeout(OPERATION_TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new McpRuntimeException("MCP HTTP 发送通知失败状态码：" + response.statusCode());
        }
    }

    private JsonNode parseSseResponse(InputStream body, int expectedId) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String eventType = null;
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (eventType != null && data.length() > 0) {
                        String eventData = data.toString().trim();
                        if ("message".equals(eventType)) {
                            JsonNode payload = objectMapper.readTree(eventData);
                            if (payload.path("id").asInt(-1) == expectedId) {
                                if (payload.hasNonNull("error")) {
                                    throw new McpRuntimeException("MCP 协议调用失败：" + payload.path("error").path("message").asText("未知错误"));
                                }
                                return payload.path("result");
                            }
                        }
                    }
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
        throw new McpRuntimeException("MCP SSE 响应流结束，未收到 id=" + expectedId + " 的结果");
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

    private static final class McpRuntimeException extends RuntimeException {
        private McpRuntimeException(String message) {
            super(message);
        }
    }
}
