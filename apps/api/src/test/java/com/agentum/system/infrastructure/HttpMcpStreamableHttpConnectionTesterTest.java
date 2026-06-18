package com.agentum.system.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentum.system.application.McpConnectionTestRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpMcpStreamableHttpConnectionTesterTest {

    private static final String REQUIRED_ACCEPT = "application/json, text/event-stream";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private int port;

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
        HttpMcpStreamableHttpConnectionTester tester = new HttpMcpStreamableHttpConnectionTester(objectMapper);

        var outcome = tester.test(new McpConnectionTestRequest(
            UUID.randomUUID(),
            "streamable_http",
            "http://127.0.0.1:" + port + "/mcp"
        ));

        assertThat(outcome.status()).isEqualTo("success");
        assertThat(outcome.tools()).extracting(tool -> tool.name()).containsExactly("demo.echo");
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

        String response = request.path("method").asText().equals("initialize")
            ? """
                {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"demo","version":"1.0.0"}}}
                """
            : """
                {"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"demo.echo","description":"回显文本","inputSchema":{"type":"object"}}]}}
                """;
        byte[] body = response.trim().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
