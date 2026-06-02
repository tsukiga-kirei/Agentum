package com.agentum.system.infrastructure;

import com.agentum.system.application.McpSseConnectionTester;
import com.agentum.system.application.McpSseTestOutcome;
import com.agentum.system.application.McpSseTestRequest;
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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class HttpMcpSseConnectionTester implements McpSseConnectionTester {

    private static final Logger log = LoggerFactory.getLogger(HttpMcpSseConnectionTester.class);
    private static final String MCP_PROTOCOL_VERSION = "2024-11-05";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration OPERATION_TIMEOUT = Duration.ofSeconds(8);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public HttpMcpSseConnectionTester(ObjectMapper objectMapper) {
        this(HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build(), objectMapper);
    }

    HttpMcpSseConnectionTester(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public McpSseTestOutcome test(McpSseTestRequest request) {
        String sseUrl = request.sseUrl() == null ? "" : request.sseUrl().trim();
        if (sseUrl.isEmpty()) {
            return failed("SSE 类型 MCP 必须配置 sseUrl");
        }

        BlockingQueue<SseEvent> events = new LinkedBlockingQueue<>();
        AtomicReference<String> messageEndpoint = new AtomicReference<>();
        AtomicBoolean sseClosed = new AtomicBoolean(false);
        Thread sseReader = new Thread(() -> readSseStream(sseUrl, events, messageEndpoint, sseClosed), "mcp-sse-reader");
        sseReader.setDaemon(true);
        sseReader.start();

        try {
            String endpointPath = waitForEndpoint(messageEndpoint, events, OPERATION_TIMEOUT);
            URI messageUri = resolveMessageUri(sseUrl, endpointPath);

            postJsonRpc(messageUri, initializeRequest(1));
            waitForJsonRpcResult(events, 1, OPERATION_TIMEOUT);

            postJsonRpc(messageUri, initializedNotification());

            postJsonRpc(messageUri, toolsListRequest(2));
            JsonNode toolsResult = waitForJsonRpcResult(events, 2, OPERATION_TIMEOUT);
            List<McpSseTestOutcome.McpToolDescriptor> tools = parseTools(toolsResult.path("tools"));

            String summary = tools.isEmpty()
                ? "MCP SSE 连接成功，但 tools/list 未返回任何工具"
                : "MCP SSE 连接成功，已通过 tools/list 读取 " + tools.size() + " 个工具";
            return new McpSseTestOutcome("success", summary, tools);
        } catch (McpProbeException ex) {
            log.warn(
                "系统管理 MCP SSE 协议测试失败 capabilityId={} sseUrl={} reason={}",
                request.capabilityId(),
                sseUrl,
                ex.getMessage()
            );
            return failed(ex.getMessage());
        } catch (Exception ex) {
            log.warn(
                "系统管理 MCP SSE 协议测试异常 capabilityId={} sseUrl={} errorType={}",
                request.capabilityId(),
                sseUrl,
                ex.getClass().getSimpleName()
            );
            return failed("MCP SSE 连接失败：" + ex.getMessage());
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
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            events.offer(SseEvent.failure("MCP SSE 连接失败：" + ex.getMessage()));
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
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remainingMillis <= 0) {
                break;
            }
            SseEvent event = events.poll(Math.min(remainingMillis, 200), TimeUnit.MILLISECONDS);
            if (event != null && event.failureMessage() != null) {
                throw new McpProbeException(event.failureMessage());
            }
        }
        throw new McpProbeException("MCP SSE 握手超时，未收到 message 端点");
    }

    private JsonNode waitForJsonRpcResult(BlockingQueue<SseEvent> events, int expectedId, Duration timeout)
        throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remainingMillis <= 0) {
                break;
            }
            SseEvent event = events.poll(Math.min(remainingMillis, 200), TimeUnit.MILLISECONDS);
            if (event == null) {
                continue;
            }
            if (event.failureMessage() != null) {
                throw new McpProbeException(event.failureMessage());
            }
            if (!"message".equals(event.eventType())) {
                continue;
            }
            JsonNode payload;
            try {
                payload = objectMapper.readTree(event.data());
            } catch (Exception ex) {
                throw new McpProbeException("MCP SSE 消息解析失败");
            }
            if (payload.path("id").asInt(-1) != expectedId) {
                continue;
            }
            if (payload.hasNonNull("error")) {
                String message = payload.path("error").path("message").asText("未知 MCP 错误");
                throw new McpProbeException("MCP 协议调用失败：" + message);
            }
            return payload.path("result");
        }
        throw new McpProbeException("MCP 协议响应超时，未收到 id=" + expectedId + " 的 JSON-RPC 结果");
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
            throw new McpProbeException("MCP message 端点返回非成功状态：" + response.statusCode());
        }
    }

    private static URI resolveMessageUri(String sseUrl, String endpointPath) {
        URI endpoint = URI.create(endpointPath);
        if (endpoint.isAbsolute()) {
            return endpoint;
        }
        URI sse = URI.create(sseUrl);
        String authority = sse.getScheme() + "://" + sse.getAuthority();
        return URI.create(authority + endpointPath);
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

    private List<McpSseTestOutcome.McpToolDescriptor> parseTools(JsonNode toolsNode) {
        if (!toolsNode.isArray()) {
            return List.of();
        }
        List<McpSseTestOutcome.McpToolDescriptor> tools = new ArrayList<>();
        for (JsonNode toolNode : toolsNode) {
            String name = toolNode.path("name").asText("").trim();
            if (name.isEmpty()) {
                continue;
            }
            String description = toolNode.path("description").asText("");
            Map<String, Object> inputSchema = readInputSchema(toolNode.path("inputSchema"));
            tools.add(new McpSseTestOutcome.McpToolDescriptor(name, description, inputSchema));
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

    private static McpSseTestOutcome failed(String summary) {
        return new McpSseTestOutcome("failed", summary, List.of());
    }

    private record SseEvent(String eventType, String data, String failureMessage) {
        static SseEvent failure(String message) {
            return new SseEvent("failure", "", message);
        }
    }

    private static final class McpProbeException extends RuntimeException {
        private McpProbeException(String message) {
            super(message);
        }
    }
}
