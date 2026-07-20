package com.agentum.mcp.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentum.mcp.application.McpRuntimeClient.ToolListRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpMcpSseRuntimeClientTest {

    private HttpServer server;
    private int port;
    private final AtomicInteger postedCount = new AtomicInteger();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/sse", new SseHandler());
        server.createContext("/mcp/message", new MessageHandler());
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldDiscoverCurrentToolsViaRuntimeSseProtocol() {
        HttpMcpSseRuntimeClient client = new HttpMcpSseRuntimeClient(objectMapper);

        var result = client.listTools(new ToolListRequest(
            UUID.randomUUID(),
            "sse",
            "http://127.0.0.1:" + port + "/sse"
        ));

        assertThat(result.tools()).extracting(tool -> tool.name()).containsExactly("demo.echo");
        assertThat(result.tools().getFirst().inputSchema().toString()).contains("text");
        assertThat(postedCount.get()).isEqualTo(3);
    }

    private final class SseHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream output = exchange.getResponseBody();
            writeEvent(output, "endpoint", "/mcp/message?sessionId=runtime-session");
            output.flush();

            Thread responder = new Thread(() -> respondOnPostedMessages(output), "fake-runtime-mcp-sse");
            responder.setDaemon(true);
            responder.start();
        }

        private void respondOnPostedMessages(OutputStream output) {
            String initializeResult = """
                {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05","capabilities":{"tools":{}},"serverInfo":{"name":"demo","version":"1.0.0"}}}
                """;
            String toolsResult = """
                {"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"demo.echo","description":"回显文本","inputSchema":{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}}]}}
                """;
            try {
                waitForPostedCount(1);
                writeEvent(output, "message", initializeResult.trim());
                output.flush();
                waitForPostedCount(3);
                writeEvent(output, "message", toolsResult.trim());
                output.flush();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (IOException exception) {
                // 测试结束后客户端会主动关闭 SSE 连接，此时无需继续写入。
            }
        }

        private void waitForPostedCount(int expected) throws InterruptedException {
            while (postedCount.get() < expected) {
                Thread.sleep(10);
            }
        }
    }

    private final class MessageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getRequestBody().readAllBytes();
            postedCount.incrementAndGet();
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        }
    }

    private static void writeEvent(OutputStream output, String eventType, String data) throws IOException {
        output.write(("event: " + eventType + "\n").getBytes(StandardCharsets.UTF_8));
        output.write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
    }
}
