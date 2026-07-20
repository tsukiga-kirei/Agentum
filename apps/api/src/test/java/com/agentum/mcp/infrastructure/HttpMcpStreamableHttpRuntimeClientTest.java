package com.agentum.mcp.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentum.mcp.application.McpRuntimeClient.ToolCall;
import com.agentum.mcp.application.McpRuntimeClient.ToolListRequest;
import com.agentum.shared.api.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpMcpStreamableHttpRuntimeClientTest {

    private static final String REQUIRED_ACCEPT = "application/json, text/event-stream";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private int port;
    private boolean repeatToolListCursor;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/mcp", this::handleMcpRequest);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldSendRequiredAcceptHeaderForInitializedNotification() {
        HttpMcpStreamableHttpRuntimeClient client = new HttpMcpStreamableHttpRuntimeClient(objectMapper);

        var result = client.callTool(new ToolCall(
            UUID.randomUUID(),
            "streamable_http",
            "http://127.0.0.1:" + port + "/mcp",
            "demo.echo",
            Map.of("text", "你好")
        ));

        assertThat(result.responsePayload()).containsEntry("text", "你好");
    }

    @Test
    void shouldDiscoverCurrentToolsViaRuntimeProtocol() {
        HttpMcpStreamableHttpRuntimeClient client = new HttpMcpStreamableHttpRuntimeClient(objectMapper);

        var result = client.listTools(new ToolListRequest(
            UUID.randomUUID(),
            "streamable_http",
            "http://127.0.0.1:" + port + "/mcp"
        ));

        assertThat(result.tools()).extracting(tool -> tool.name()).containsExactly("demo.echo", "demo.upper");
        assertThat(result.tools().getFirst().inputSchema().toString()).contains("text");
    }

    @Test
    void shouldRejectRepeatedToolListCursor() {
        repeatToolListCursor = true;
        HttpMcpStreamableHttpRuntimeClient client = new HttpMcpStreamableHttpRuntimeClient(objectMapper);

        assertThatThrownBy(() -> client.listTools(new ToolListRequest(
            UUID.randomUUID(),
            "streamable_http",
            "http://127.0.0.1:" + port + "/mcp"
        )))
            .isInstanceOf(ApiException.class)
            .satisfies(error -> assertThat(((ApiException) error).getCode()).isEqualTo("MCP_TOOL_DISCOVERY_FAILED"));
    }

    private void handleMcpRequest(HttpExchange exchange) throws IOException {
        String accept = exchange.getRequestHeaders().getFirst("Accept");
        if (!REQUIRED_ACCEPT.equals(accept)) {
            byte[] body = "缺少 Streamable HTTP Accept 请求头".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(406, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
            return;
        }

        JsonNode request = objectMapper.readTree(exchange.getRequestBody());
        if (!request.has("id")) {
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            return;
        }

        String response = switch (request.path("method").asText()) {
            case "initialize" -> """
                {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05","capabilities":{"tools":{}},"serverInfo":{"name":"demo","version":"1.0.0"}}}
                """;
            case "tools/list" -> request.path("params").path("cursor").asText("").isBlank()
                ? """
                    {"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"demo.echo","description":"回显文本","inputSchema":{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}}],"nextCursor":"page-2"}}
                    """
                : repeatToolListCursor
                    ? """
                        {"jsonrpc":"2.0","id":3,"result":{"tools":[{"name":"demo.upper","description":"转换大写","inputSchema":{"type":"object"}}],"nextCursor":"page-2"}}
                        """
                    : """
                        {"jsonrpc":"2.0","id":3,"result":{"tools":[{"name":"demo.upper","description":"转换大写","inputSchema":{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}}]}}
                        """;
            default -> """
                {"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"你好"}],"isError":false}}
                """;
        };
        byte[] body = response.trim().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
