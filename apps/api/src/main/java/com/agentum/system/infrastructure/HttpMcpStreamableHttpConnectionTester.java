package com.agentum.system.infrastructure;

import com.agentum.system.application.McpConnectionTester;
import com.agentum.system.application.McpConnectionTestOutcome;
import com.agentum.system.application.McpConnectionTestRequest;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component("mcpStreamableHttpConnectionTester")
public class HttpMcpStreamableHttpConnectionTester implements McpConnectionTester {

    private static final Logger log = LoggerFactory.getLogger(HttpMcpStreamableHttpConnectionTester.class);
    private static final String MCP_PROTOCOL_VERSION = "2024-11-05";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration OPERATION_TIMEOUT = Duration.ofSeconds(8);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public HttpMcpStreamableHttpConnectionTester(ObjectMapper objectMapper) {
        this(HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build(), objectMapper);
    }

    HttpMcpStreamableHttpConnectionTester(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public McpConnectionTestOutcome test(McpConnectionTestRequest request) {
        String endpointUrl = request.endpointUrl() == null ? "" : request.endpointUrl().trim();
        if (endpointUrl.isEmpty()) {
            return failed("Streamable HTTP 类型 MCP 必须配置 endpointUrl");
        }

        try {
            URI endpointUri = URI.create(endpointUrl);

            // 1. initialize
            postJsonRpcAndGetResult(endpointUri, initializeRequest(1), 1);

            // 2. initialized notification
            postNotification(endpointUri, initializedNotification());

            // 3. tools/list
            JsonNode toolsResult = postJsonRpcAndGetResult(endpointUri, toolsListRequest(2), 2);
            List<McpConnectionTestOutcome.McpToolDescriptor> tools = parseTools(toolsResult.path("tools"));

            String summary = tools.isEmpty()
                ? "MCP Streamable HTTP 连接成功，但 tools/list 未返回任何工具"
                : "MCP Streamable HTTP 连接成功，已通过 tools/list 读取 " + tools.size() + " 个工具";
            return new McpConnectionTestOutcome("success", summary, tools);
        } catch (McpProbeException ex) {
            log.warn(
                "系统管理 MCP Streamable HTTP 协议测试失败 capabilityId={} endpointUrl={} reason={}",
                request.capabilityId(),
                endpointUrl,
                ex.getMessage()
            );
            return failed(ex.getMessage());
        } catch (Exception ex) {
            log.warn(
                "系统管理 MCP Streamable HTTP 协议测试异常 capabilityId={} endpointUrl={} errorType={}",
                request.capabilityId(),
                endpointUrl,
                ex.getClass().getSimpleName(),
                ex
            );
            return failed("MCP Streamable HTTP 连接失败：" + ex.getMessage());
        }
    }

    private JsonNode postJsonRpcAndGetResult(URI endpointUri, ObjectNode payload, int expectedId) throws Exception {
        String body = objectMapper.writeValueAsString(payload);
        HttpRequest request = HttpRequest.newBuilder(endpointUri)
            .timeout(OPERATION_TIMEOUT)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("X-Request-Id", RequestIds.current())
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new McpProbeException("MCP HTTP 接口返回错误状态码：" + response.statusCode());
        }

        String contentType = response.headers().firstValue("Content-Type").orElse("application/json").toLowerCase();
        if (contentType.contains("text/event-stream")) {
            return parseSseResponse(response.body(), expectedId);
        } else {
            try (InputStream is = response.body()) {
                JsonNode root = objectMapper.readTree(is);
                if (root.path("id").asInt(-1) != expectedId) {
                    throw new McpProbeException("MCP HTTP 响应 ID 不匹配：" + root.path("id").asInt(-1));
                }
                if (root.hasNonNull("error")) {
                    String message = root.path("error").path("message").asText("未知 MCP 错误");
                    throw new McpProbeException("MCP 协议调用失败：" + message);
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
            // Streamable HTTP 要求通知类 POST 同样声明两种响应类型，否则严格实现会返回 406。
            .header("Accept", "application/json, text/event-stream")
            .header("X-Request-Id", RequestIds.current())
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new McpProbeException("MCP HTTP 发送通知失败状态码：" + response.statusCode());
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
                                    String message = payload.path("error").path("message").asText("未知 MCP 错误");
                                    throw new McpProbeException("MCP 协议调用失败：" + message);
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
        throw new McpProbeException("MCP SSE 响应流结束，未收到 id=" + expectedId + " 的结果");
    }

    private ObjectNode initializeRequest(int id) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("protocolVersion", MCP_PROTOCOL_VERSION);
        params.set("capabilities", objectMapper.createObjectNode());
        ObjectNode clientInfo = objectMapper.createObjectNode();
        clientInfo.put("name", "agentum-system-admin");
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

    private ObjectNode toolsListRequest(int id) {
        return jsonRpcRequest(id, "tools/list", objectMapper.createObjectNode());
    }

    private ObjectNode jsonRpcRequest(int id, String method, JsonNode params) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("jsonrpc", "2.0");
        payload.put("id", id);
        payload.put("method", method);
        payload.set("params", params);
        return payload;
    }

    private List<McpConnectionTestOutcome.McpToolDescriptor> parseTools(JsonNode toolsNode) {
        if (!toolsNode.isArray()) {
            return List.of();
        }
        List<McpConnectionTestOutcome.McpToolDescriptor> tools = new ArrayList<>();
        for (JsonNode toolNode : toolsNode) {
            String name = toolNode.path("name").asText("").trim();
            if (name.isEmpty()) {
                continue;
            }
            String description = toolNode.path("description").asText("");
            Map<String, Object> inputSchema = readInputSchema(toolNode.path("inputSchema"));
            tools.add(new McpConnectionTestOutcome.McpToolDescriptor(name, description, inputSchema));
        }
        return tools;
    }

    private Map<String, Object> readInputSchema(JsonNode schemaNode) {
        if (schemaNode.isMissingNode() || schemaNode.isNull()) {
            return Map.of("type", "object", "properties", Map.of());
        }
        return objectMapper.convertValue(schemaNode, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    private static McpConnectionTestOutcome failed(String summary) {
        return new McpConnectionTestOutcome("failed", summary, List.of());
    }

    private static final class McpProbeException extends RuntimeException {
        private McpProbeException(String message) {
            super(message);
        }
    }
}
