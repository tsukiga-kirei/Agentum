package com.agentum.attachment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.agentum.attachment.domain.AttachmentRecognitionSettingEntity;
import com.agentum.shared.api.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 真实 HTTP 替身覆盖固定 4.0 协议、代理前缀、超时及凭证边界，不调用目标 MinerU。 */
class Mineru4AttachmentParserTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AttachmentRecognitionSettingsService service = mock(AttachmentRecognitionSettingsService.class);
    private final AttachmentRecognitionSettingEntity settings = mock(AttachmentRecognitionSettingEntity.class);
    private final Mineru4AttachmentParser parser = new Mineru4AttachmentParser(mapper, service);
    private final List<HttpServer> servers = new ArrayList<>();
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private boolean cached;
    private String firstJob = "queued";
    private String finalJob = "completed";
    private String customJob;
    private String uploadTarget = "/v1/uploads/up/content";
    private String resultTarget;

    @BeforeEach
    void setup() throws Exception {
        HttpServer api = server(this::handle);
        when(settings.getMineruEndpoint()).thenReturn(address(api) + "/mineru");
        when(settings.getMineruConnectTimeoutSeconds()).thenReturn(2);
        when(settings.getMineruReadTimeoutSeconds()).thenReturn(10);
        when(settings.getMineruTier()).thenReturn("advanced");
        when(settings.getMineruOcrMode()).thenReturn("ocr");
        when(service.decryptMineruApiKey(settings)).thenReturn("api-secret");
    }

    @AfterEach
    void cleanup() {
        servers.forEach(server -> server.stop(0));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void uploadsOrReusesCachedFileAndDownloadsMarkdown(boolean cacheHit) throws Exception {
        cached = cacheHit;
        byte[] source = "%PDF-合同".getBytes(StandardCharsets.UTF_8);
        assertThat(parser.parse(settings, "合同.pdf", source)).isEqualTo("# 识别正文");
        assertThat(calls.stream().map(Call::path)).containsExactlyElementsOf(cacheHit
            ? List.of("/mineru/v1/uploads", "/mineru/v1/parse/jobs", "/mineru/v1/parse/jobs/job", "/mineru/v1/files/md/content")
            : List.of("/mineru/v1/uploads", "/mineru/v1/uploads/up/content", "/mineru/v1/uploads/up/complete",
                "/mineru/v1/parse/jobs", "/mineru/v1/parse/jobs/job", "/mineru/v1/files/md/content"));
        JsonNode createUpload = mapper.readTree(calls.get(0).body());
        assertThat(createUpload.path("filename").asText()).isEqualTo("合同.pdf");
        assertThat(createUpload.path("bytes").asInt()).isEqualTo(source.length);
        assertThat(createUpload.path("sha256sum").asText()).hasSize(64);
        if (!cacheHit) {
            assertThat(calls.get(1).method()).isEqualTo("PUT");
            assertThat(calls.get(1).body()).isEqualTo(source);
            assertThat(calls.get(1).uploadHeader()).isEqualTo("signed-value");
        }
        assertThat(calls).allSatisfy(call -> assertThat(call.authorization()).isEqualTo("Bearer api-secret"));
        JsonNode job = mapper.readTree(calls.stream().filter(call -> call.path().equals("/mineru/v1/parse/jobs")).findFirst().orElseThrow().body());
        assertThat(job.path("tier").asText()).isEqualTo("advanced");
        assertThat(job.path("ocr_mode").asText()).isEqualTo("ocr");
        assertThat(job.path("files").get(0).path("source").path("file_id").asText()).isEqualTo("file");
        assertThat(job.path("output_formats").get(0).asText()).isEqualTo("markdown");
        assertThat(job.has("backend") || job.has("language") || job.has("formula_enable")).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"a.docx, advanced, flash, auto", "a.xlsx, standard, flash, txt", "a.csv, basic, flash, ocr",
        "a.ofd, advanced, flash, auto", "a.pdf, flash, flash, txt", "a.png, basic, basic, auto", "a.pdf, standard, standard, ocr"})
    void sendsSelectedQualityAndOcrForPdfImagesAndFlashForNativeDocuments(String name, String selected, String actual, String ocr) throws Exception {
        cached = true;
        firstJob = "completed";
        when(settings.getMineruTier()).thenReturn(selected);
        when(settings.getMineruOcrMode()).thenReturn(ocr);
        parser.parse(settings, name, new byte[]{1});
        JsonNode payload = mapper.readTree(calls.get(1).body());
        assertThat(payload.path("tier").asText()).isEqualTo(actual);
        assertThat(payload.path("ocr_mode").asText()).isEqualTo(ocr);
        assertThat(payload.path("files").get(0).has("page_range")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"failed", "partial", "canceled", "unknown"})
    void doesNotDownloadTerminalFailuresOrUnknownStates(String status) {
        cached = true;
        firstJob = status;
        assertThatThrownBy(() -> parser.parse(settings, "a.pdf", new byte[]{1})).isInstanceOf(ApiException.class);
        assertThat(calls).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "not-json", "null", "{\"job_id\":\"job\",\"status\":\"completed\",\"files\":[]}",
        "{\"job_id\":\"job\",\"status\":\"completed\",\"files\":[{\"status\":\"failed\",\"error\":{\"message\":\"sensitive-content\"}}]}",
        "{\"job_id\":\"job\",\"status\":\"completed\",\"files\":[{\"status\":\"completed\",\"output_files\":{}}]}"})
    void rejectsMalformedAndIncompleteResponsesWithoutLeakingVendorMessage(String response) {
        cached = true;
        customJob = response;
        assertThatThrownBy(() -> parser.parse(settings, "a.pdf", new byte[]{1}))
            .isInstanceOf(ApiException.class).hasMessageNotContaining("sensitive-content");
    }

    @Test
    void totalTimeoutIncludesPollingAndDoesNotDownloadResult() {
        cached = true;
        finalJob = "running";
        when(settings.getMineruReadTimeoutSeconds()).thenReturn(1);
        long started = System.nanoTime();
        assertThatThrownBy(() -> parser.parse(settings, "a.pdf", new byte[]{1}))
            .isInstanceOf(ApiException.class).satisfies(error -> assertThat(((ApiException) error).getCode()).isEqualTo("ATTACHMENT_MINERU4_TIMEOUT"));
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(3000);
        assertThat(calls).noneMatch(call -> call.path().contains("/files/"));
    }

    @Test
    void signedUploadAndRedirectedDownloadDoNotReceiveApiCredentials() throws Exception {
        List<Call> external = new CopyOnWriteArrayList<>();
        HttpServer objectStorage = server(exchange -> {
            external.add(record(exchange));
            reply(exchange, 200, exchange.getRequestMethod().equals("PUT") ? "" : "# 识别正文");
        });
        uploadTarget = address(objectStorage) + "/signed-upload";
        resultTarget = address(objectStorage) + "/signed-result";
        firstJob = "completed";
        assertThat(parser.parse(settings, "a.pdf", new byte[]{1})).isEqualTo("# 识别正文");
        assertThat(external).hasSize(2).allSatisfy(call -> assertThat(call.authorization()).isNull());
        assertThat(external.get(0).uploadHeader()).isEqualTo("signed-value");
        assertThat(external.get(1).uploadHeader()).isNull();
    }

    @Test
    void dispatcherUsesExplicitParserChoice() {
        var v4 = mock(Mineru4AttachmentParser.class);
        when(settings.getMineruParser()).thenReturn("mineru4");
        byte[] bytes = new byte[]{1};
        when(v4.parse(settings, "a.pdf", bytes)).thenReturn("新版正文");
        assertThat(new MineruAttachmentParser(mapper, service, v4).parse(settings, "a.pdf", bytes)).isEqualTo("新版正文");
        verify(v4).parse(settings, "a.pdf", bytes);
    }

    private void handle(HttpExchange exchange) throws IOException {
        Call call = record(exchange);
        calls.add(call);
        String path = call.path();
        if (path.equals("/mineru/v1/uploads")) {
            reply(exchange, 200, cached ? "{\"id\":\"up\",\"status\":\"completed\",\"file\":{\"id\":\"file\"}}"
                : mapper.writeValueAsString(java.util.Map.of("id", "up", "status", "pending", "upload_url", uploadTarget,
                    "upload_method", "PUT", "upload_headers", java.util.Map.of("X-Upload", "signed-value"))));
        } else if (path.endsWith("/up/content")) {
            reply(exchange, 200, "");
        } else if (path.endsWith("/up/complete")) {
            reply(exchange, 200, "{\"id\":\"up\",\"status\":\"completed\",\"file\":{\"id\":\"file\"}}");
        } else if (path.equals("/mineru/v1/parse/jobs")) {
            reply(exchange, 200, customJob == null ? job(firstJob) : customJob);
        } else if (path.endsWith("/parse/jobs/job")) {
            reply(exchange, 200, job(finalJob));
        } else if (path.endsWith("/files/md/content")) {
            if (resultTarget != null) exchange.getResponseHeaders().add("Location", resultTarget);
            reply(exchange, resultTarget == null ? 200 : 302, "# 识别正文");
        } else reply(exchange, 404, "missing");
    }

    private String job(String status) {
        return "{\"job_id\":\"job\",\"status\":\"" + status + "\",\"files\":[{\"status\":\"completed\",\"output_files\":{\"markdown\":{\"file_id\":\"md\"}}}]}";
    }

    private HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        servers.add(server);
        return server;
    }

    private static String address(HttpServer server) { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    private static Call record(HttpExchange exchange) throws IOException {
        return new Call(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
            exchange.getRequestHeaders().getFirst("Authorization"), exchange.getRequestHeaders().getFirst("X-Upload"), exchange.getRequestBody().readAllBytes());
    }

    private static void reply(HttpExchange exchange, int status, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record Call(String method, String path, String authorization, String uploadHeader, byte[] body) { }
}
