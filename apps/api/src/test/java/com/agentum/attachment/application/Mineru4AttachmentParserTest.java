package com.agentum.attachment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.agentum.attachment.domain.AttachmentRecognitionSettingEntity;
import com.agentum.shared.api.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** HTTP 替身按官方 4.1.7 Tasks 快照验证上传、逐文件 ID、ZIP 读取与凭证边界。 */
class Mineru4AttachmentParserTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AttachmentRecognitionSettingsService service = mock(AttachmentRecognitionSettingsService.class);
    private final AttachmentRecognitionSettingEntity settings = mock(AttachmentRecognitionSettingEntity.class);
    private final Mineru4AttachmentParser parser = new Mineru4AttachmentParser(mapper, service);
    private final List<HttpServer> servers = new ArrayList<>();
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final ArrayDeque<ObjectNode> snapshots = new ArrayDeque<>();
    private ObjectNode creation;
    private String createBody;
    private int createStatus = 202;
    private byte[] resultZip;
    private String redirectTarget;
    private boolean keepRunning;

    @BeforeEach
    void setup() throws Exception {
        HttpServer api = server(this::handle);
        when(settings.getMineruEndpoint()).thenReturn(address(api) + "/mineru");
        when(settings.getMineruConnectTimeoutSeconds()).thenReturn(2);
        when(settings.getMineruReadTimeoutSeconds()).thenReturn(10);
        when(settings.getMineruTier()).thenReturn("advanced");
        when(settings.getMineruOcrMode()).thenReturn("ocr");
        when(service.decryptMineruApiKey(settings)).thenReturn("api-secret");
        creation = task("waiting-file", "waiting-file");
        resultZip = zip(Map.of("markdown.md", "# 识别正文"));
    }

    @AfterEach
    void cleanup() { servers.forEach(server -> server.stop(0)); }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void tasksUploadOrCacheHitDownloadsZipWithoutLegacyEndpoints(boolean cacheHit) throws Exception {
        if (cacheHit) creation = task("completed", "completed");
        byte[] source = "%PDF-合同".getBytes(StandardCharsets.UTF_8);
        assertThat(parser.parse(settings, "合同.pdf", source)).isEqualTo("# 识别正文");
        assertThat(calls.stream().map(Call::path)).containsExactlyElementsOf(cacheHit
            ? List.of("/mineru/v1/tasks", "/mineru/v1/files/file/result")
            : List.of("/mineru/v1/tasks", "/mineru/upload/file", "/mineru/v1/tasks/task", "/mineru/v1/files/file/result"));
        JsonNode request = mapper.readTree(calls.get(0).body());
        JsonNode file = request.path("files").get(0);
        assertThat(file.path("file_name").asText()).isEqualTo("合同.pdf");
        assertThat(file.path("bytes").asInt()).isEqualTo(source.length);
        assertThat(file.path("sha256sum").asText()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source)));
        assertThat(request.path("tier").asText()).isEqualTo("advanced");
        assertThat(request.path("ocr_mode").asText()).isEqualTo("ocr");
        assertThat(request.path("output_formats").get(0).asText()).isEqualTo("default");
        assertThat(file.has("filename") || file.has("file_id") || file.has("source") || request.has("backend")).isFalse();
        assertThat(calls).allSatisfy(call -> assertThat(call.authorization()).isEqualTo("Bearer api-secret"));
        if (!cacheHit) {
            assertThat(calls.get(1).method()).isEqualTo("PUT");
            assertThat(calls.get(1).body()).isEqualTo(source);
            assertThat(calls.get(1).uploadHeader()).isEqualTo("signed-value");
        }
    }

    @ParameterizedTest
    @CsvSource({"a.docx,advanced,flash,auto", "a.xlsx,standard,flash,txt", "a.csv,basic,flash,ocr",
        "a.ofd,advanced,flash,auto", "a.pdf,flash,flash,txt", "a.png,basic,basic,auto", "a.pdf,standard,standard,ocr"})
    void sendsConfiguredQualityAndOcrWithFlashForNativeDocuments(String name, String selected, String actual, String ocr) throws Exception {
        creation = task("completed", "completed");
        when(settings.getMineruTier()).thenReturn(selected);
        when(settings.getMineruOcrMode()).thenReturn(ocr);
        parser.parse(settings, name, new byte[]{1});
        JsonNode payload = mapper.readTree(calls.get(0).body());
        assertThat(payload.path("tier").asText()).isEqualTo(actual);
        assertThat(payload.path("ocr_mode").asText()).isEqualTo(ocr);
        assertThat(payload.path("files").get(0).has("page_range")).isFalse();
    }

    @Test
    void waitingForFileWithoutUploadOnlyPollsAndUploadsOnceWhenUrlAppears() {
        ((ObjectNode) creation.path("files").get(0)).remove("upload");
        snapshots.add(task("waiting-file", "waiting-file"));
        snapshots.add(task("waiting-file", "waiting-file"));
        snapshots.add(task("pending", "pending"));
        snapshots.add(task("running", "running"));
        assertThat(parser.parse(settings, "a.pdf", new byte[]{1})).isEqualTo("# 识别正文");
        assertThat(calls.stream().filter(call -> call.method().equals("PUT")).count()).isEqualTo(1);
        assertThat(calls.stream().filter(call -> call.method().equals("POST")).count()).isEqualTo(1);
    }

    @Test
    void completedFileCanBeReadWhileParentStillRunning() {
        creation = task("running", "completed");
        assertThat(parser.parse(settings, "a.pdf", new byte[]{1})).isEqualTo("# 识别正文");
        assertThat(calls).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"failed", "partial", "canceled", "unknown"})
    void rejectsParentFailureOrUnknownStatusWithoutDownloading(String status) {
        creation = task(status, "completed");
        assertThatThrownBy(() -> parser.parse(settings, "a.pdf", new byte[]{1})).isInstanceOf(ApiException.class);
        assertThat(calls).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"failed", "canceled", "partial", "unknown"})
    void rejectsFailedOrInvalidFileEvenWithRunningParent(String status) {
        creation = task("running", status);
        ((ObjectNode) creation.path("files").get(0)).putObject("error").put("message", "sensitive-content");
        assertThatThrownBy(() -> parser.parse(settings, "a.pdf", new byte[]{1}))
            .isInstanceOf(ApiException.class).hasMessageNotContaining("sensitive-content");
        assertThat(calls).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"task_id", "file_id"})
    void rejectsChangedTaskOrFileIdentity(String field) {
        creation = task("pending", "pending");
        ObjectNode changed = task("completed", "completed");
        if (field.equals("task_id")) changed.put(field, "another-task");
        else ((ObjectNode) changed.path("files").get(0)).put(field, "another-file");
        snapshots.add(changed);
        assertThatThrownBy(() -> parser.parse(settings, "a.pdf", new byte[]{1})).isInstanceOf(ApiException.class).hasMessageContaining("标识不匹配");
        assertThat(calls).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "not-json", "null", "{\"task_id\":\"task\",\"status\":\"completed\",\"files\":[]}",
        "{\"task_id\":\"task\",\"status\":\"completed\",\"files\":[{\"file_id\":\"file\",\"status\":\"pending\"}]}",
        "{\"task_id\":\"task\",\"status\":\"completed\",\"files\":[{\"file_id\":\"file\",\"status\":\"completed\"}]}"})
    void malformedOrIncompleteResponseFails(String response) {
        createBody = response;
        assertThatThrownBy(() -> parser.parse(settings, "a.pdf", new byte[]{1})).isInstanceOf(ApiException.class);
        assertThat(calls).hasSize(1);
    }

    @Test
    void httpFailureDoesNotResubmitTaskOrLeakResponse() {
        createStatus = 422;
        createBody = "{\"error\":{\"message\":\"sensitive-content\"}}";
        assertThatThrownBy(() -> parser.parse(settings, "a.pdf", new byte[]{1}))
            .isInstanceOf(ApiException.class).hasMessageContaining("HTTP 422").hasMessageNotContaining("sensitive-content");
        assertThat(calls).hasSize(1);
    }

    @Test
    void totalTimeoutIncludesWaitingForFileAndPolling() {
        creation = task("pending", "pending");
        keepRunning = true;
        when(settings.getMineruReadTimeoutSeconds()).thenReturn(1);
        long started = System.nanoTime();
        assertThatThrownBy(() -> parser.parse(settings, "a.pdf", new byte[]{1}))
            .isInstanceOf(ApiException.class).satisfies(error -> assertThat(((ApiException) error).getCode()).isEqualTo("ATTACHMENT_MINERU4_TIMEOUT"));
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(3000);
        assertThat(calls).noneMatch(call -> call.path().contains("/files/"));
    }

    @Test
    void signedUploadAndRedirectedDownloadIsolateCredentialsAndKeepSignedHeaders() throws Exception {
        List<Call> external = new CopyOnWriteArrayList<>();
        HttpServer objectStorage = server(exchange -> {
            external.add(record(exchange));
            reply(exchange, 200, exchange.getRequestMethod().equals("PUT") ? new byte[0] : resultZip);
        });
        ((ObjectNode) creation.path("files").get(0).path("upload")).put("url", address(objectStorage) + "/signed-upload");
        redirectTarget = address(objectStorage) + "/signed-result";
        assertThat(parser.parse(settings, "a.pdf", new byte[]{1})).isEqualTo("# 识别正文");
        assertThat(external).hasSize(2).allSatisfy(call -> assertThat(call.authorization()).isNull());
        assertThat(external.get(0).uploadHeader()).isEqualTo("signed-value");
        assertThat(external.get(1).uploadHeader()).isNull();
    }

    @Test
    void resultUrlWithApiPathIsUsedExactlyInsteadOfDuplicatingProxyPrefix() {
        creation = task("completed", "completed");
        ((ObjectNode) creation.path("files").get(0)).put("result_url", "/v1/files/file/result");
        parser.parse(settings, "a.pdf", new byte[]{1});
        assertThat(calls.get(1).path()).isEqualTo("/v1/files/file/result");
    }

    @Test
    void zipPrefersCanonicalMarkdownOverFallbackAndSkipsMacMetadata() throws Exception {
        creation = task("completed", "completed");
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("__MACOSX/markdown.md", "元数据");
        entries.put("readme.md", "其他说明");
        entries.put("nested/markdown.md", "正式正文");
        resultZip = zip(entries);
        assertThat(parser.parse(settings, "a.pdf", new byte[]{1})).isEqualTo("正式正文");
        resultZip = zip(Map.of("nested/output.md", "备用正文"));
        assertThat(parser.parse(settings, "a.pdf", new byte[]{1})).isEqualTo("备用正文");
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "missing", "empty", "oversized"})
    void rejectsInvalidEmptyOrOversizedMarkdownZip(String kind) throws Exception {
        creation = task("completed", "completed");
        resultZip = switch (kind) {
            case "invalid" -> "not-zip".getBytes(StandardCharsets.UTF_8);
            case "missing" -> zip(Map.of("result.json", "{}"));
            case "oversized" -> zip(Map.of("markdown.md", "x".repeat(32 * 1024 * 1024 + 1)));
            default -> zip(Map.of("markdown.md", " \n"));
        };
        assertThatThrownBy(() -> parser.parse(settings, "a.pdf", new byte[]{1})).isInstanceOf(ApiException.class);
    }

    @Test
    void zipDecompressionHonorsTotalDeadline() throws Exception {
        byte[] bytes = zip(Map.of("markdown.md", "正文"));
        assertThatThrownBy(() -> Mineru4AttachmentParser.readMarkdownZip(bytes, System.nanoTime() - 1))
            .isInstanceOf(java.net.http.HttpTimeoutException.class);
    }

    @Test
    void sanitizesOnlySubmittedFilenameAndPreservesExtension() throws Exception {
        creation = task("completed", "completed");
        parser.parse(settings, "C:\\dir\\合同:副本\n.pdf", new byte[]{1});
        assertThat(mapper.readTree(calls.get(0).body()).path("files").get(0).path("file_name").asText()).isEqualTo("合同_副本_.pdf");
        assertThat(Mineru4AttachmentParser.safeFileName("report..csv")).isEqualTo("report..csv");
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
        if (call.path().equals("/mineru/v1/tasks") && call.method().equals("POST")) {
            reply(exchange, createStatus, (createBody == null ? creation.toString() : createBody).getBytes(StandardCharsets.UTF_8));
        } else if (call.path().equals("/mineru/upload/file")) {
            reply(exchange, 200, new byte[0]);
        } else if (call.path().equals("/mineru/v1/tasks/task")) {
            ObjectNode detail = !snapshots.isEmpty() ? snapshots.removeFirst() : keepRunning ? task("running", "running") : task("completed", "completed");
            reply(exchange, 200, detail.toString().getBytes(StandardCharsets.UTF_8));
        } else if (call.path().endsWith("/files/file/result")) {
            if (redirectTarget != null) exchange.getResponseHeaders().add("Location", redirectTarget);
            reply(exchange, redirectTarget == null ? 200 : 302, resultZip);
        } else reply(exchange, 404, new byte[0]);
    }

    private ObjectNode task(String status, String fileStatus) {
        ObjectNode task = mapper.createObjectNode().put("task_id", "task").put("status", status);
        ObjectNode file = task.putArray("files").addObject().put("file_id", "file").put("file_name", "a.pdf").put("status", fileStatus);
        if (fileStatus.equals("completed")) file.put("result_url", "/mineru/v1/files/file/result");
        if (fileStatus.equals("waiting-file")) file.putObject("upload").put("url", "/mineru/upload/file")
            .putObject("headers").put("X-Upload", "signed-value");
        return task;
    }

    private static byte[] zip(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return output.toByteArray();
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

    private static void reply(HttpExchange exchange, int status, byte[] bytes) throws IOException {
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record Call(String method, String path, String authorization, String uploadHeader, byte[] body) { }
}
