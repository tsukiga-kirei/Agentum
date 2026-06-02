package com.agentum.system.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentum.system.application.McpSseTestRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpMcpSseConnectionTesterTest {

    private HttpServer server;
    private int port;
    private BlockingQueue<String> postedBodies;
    private final AtomicInteger postedCount = new AtomicInteger();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        postedBodies = new LinkedBlockingQueue<>();
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
    void shouldListToolsViaStandardMcpProtocol() {
        HttpMcpSseConnectionTester tester = new HttpMcpSseConnectionTester(objectMapper);
        var outcome = tester.test(new McpSseTestRequest(UUID.randomUUID(), "http://127.0.0.1:" + port + "/sse"));

        assertThat(outcome.status()).isEqualTo("success");
        assertThat(outcome.summary()).contains("tools/list");
        assertThat(outcome.tools()).extracting(tool -> tool.name()).containsExactly("demo.echo");
        assertThat(postedCount.get()).isEqualTo(3);
    }

    private final class SseHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream output = exchange.getResponseBody();
            writeEvent(output, "endpoint", "/mcp/message?sessionId=test-session");
            output.flush();

            Thread responder = new Thread(() -> respondOnPostedMessages(output), "fake-mcp-sse");
            responder.setDaemon(true);
            responder.start();
        }

        private void respondOnPostedMessages(OutputStream output) {
            String initializeResult = """
                {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"demo","version":"1.0.0"}}}
                """;
            String toolsResult = """
                {"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"demo.echo","description":"回显文本","inputSchema":{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}}]}}
                """;
            try {
                while (postedCount.get() < 1) {
                    Thread.sleep(20);
                }
                writeEvent(output, "message", initializeResult.trim());
                output.flush();
                while (postedCount.get() < 3) {
                    Thread.sleep(20);
                }
                writeEvent(output, "message", toolsResult.trim());
                output.flush();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (IOException ex) {
                // 测试结束后连接关闭时忽略写入异常。
            }
        }
    }

    private final class MessageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            postedBodies.offer(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
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
