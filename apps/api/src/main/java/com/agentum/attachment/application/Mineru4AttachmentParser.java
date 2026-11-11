package com.agentum.attachment.application;

import com.agentum.attachment.domain.AttachmentRecognitionSettingEntity;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.RequestIds;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 按 MinerU 4.1.7 固定版本接入 Tasks → 按需 PUT → 逐文件结果 ZIP，不保留 4.0 Jobs 路径。 */
@Component
public class Mineru4AttachmentParser {

    private static final Logger log = LoggerFactory.getLogger(Mineru4AttachmentParser.class);
    private static final Set<String> NATIVE_EXTENSIONS = Set.of(
        "doc", "docx", "ppt", "pptx", "xls", "xlsx", "rtf", "odt", "ods", "odp", "epub", "ofd", "html", "htm", "csv", "tsv"
    );
    private final ObjectMapper mapper;
    private final AttachmentRecognitionSettingsService settingsService;

    public Mineru4AttachmentParser(ObjectMapper mapper, AttachmentRecognitionSettingsService settingsService) {
        this.mapper = mapper;
        this.settingsService = settingsService;
    }

    public String parse(AttachmentRecognitionSettingEntity settings, String fileName, byte[] bytes) {
        long started = System.nanoTime();
        try {
            // 上传、排队、轮询与下载共用总预算，避免每次轮询重新获得完整超时而长期占用消费者。
            long deadline = started + Duration.ofSeconds(settings.getMineruReadTimeoutSeconds()).toNanos();
            URI root = serviceRoot(settings.getMineruEndpoint());
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(settings.getMineruConnectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER).build();
            String apiKey = settingsService.decryptMineruApiKey(settings);
            if (bytes.length == 0) throw protocol("文件内容为空");
            String safeName = safeFileName(fileName);
            String extension = safeName.substring(safeName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            // 原生文档固定 Flash；PDF/图片显式选档，不因远端资源不足而静默降级。
            String tier = NATIVE_EXTENSIONS.contains(extension) ? "flash" : settings.getMineruTier();
            JsonNode task = json(client, root, apiKey, "POST", root + "/v1/tasks", Map.of(
                "files", List.of(Map.of("file_name", safeName, "bytes", bytes.length,
                    "sha256sum", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))),
                "tier", tier, "ocr_mode", settings.getMineruOcrMode(), "output_formats", List.of("default")
            ), deadline);
            String taskId = requiredText(task, "task_id");
            log.info("MinerU 4.1.7 解析任务已创建 tenantId={} userId={} taskId={} tier={} ocrMode={} requestId={}",
                MDC.get("tenantId"), MDC.get("userId"), taskId, tier, settings.getMineruOcrMode(), RequestIds.current());
            String fileId = null;
            boolean uploaded = false;
            String resultUrl;
            for (int polls = 0; ; polls++) {
                // 每次响应是完整快照，文件始终按创建回执分配的 ID 关联，不能沿用旧链接或按名称猜测。
                if (!taskId.equals(requiredText(task, "task_id"))) throw protocol("解析任务标识不匹配");
                JsonNode file = taskFile(task);
                String currentId = requiredText(file, "file_id");
                if (fileId != null && !fileId.equals(currentId)) throw protocol("解析文件标识不匹配");
                fileId = currentId;
                String status = requiredText(file, "status");
                if ("completed".equals(status)) {
                    resultUrl = requiredText(file, "result_url");
                    break;
                }
                if ("waiting-file".equals(status) && !uploaded && file.hasNonNull("upload")) {
                    JsonNode upload = file.path("upload");
                    Map<String, String> headers = new LinkedHashMap<>();
                    JsonNode uploadHeaders = upload.path("headers");
                    if (!uploadHeaders.isMissingNode() && !uploadHeaders.isObject()) throw protocol("上传请求头无效");
                    uploadHeaders.fields().forEachRemaining(field -> {
                        if (!field.getValue().isTextual()) throw protocol("上传请求头无效");
                        // HTTP 客户端根据实际字节生成长度与 Host，不能沿用供应商传来的受限头。
                        if (!Set.of("host", "content-length").contains(field.getKey().toLowerCase(Locale.ROOT))) {
                            headers.put(field.getKey(), field.getValue().asText());
                        }
                    });
                    request(client, root, apiKey, "PUT", requiredText(upload, "url"), bytes, headers, deadline);
                    uploaded = true;
                }
                // waiting-file 无 upload 表示服务正在准备文件，只有轮询，不调用 complete 或重新创建任务。
                if (polls >= 3600) throw new HttpTimeoutException("poll limit");
                Duration budget = remaining(deadline);
                Thread.sleep(Math.min(1000, Math.max(1, budget.toMillis())));
                task = json(client, root, apiKey, "GET", root + "/v1/tasks/" + encode(taskId), null, deadline);
            }
            byte[] result = request(client, root, apiKey, "GET", resultUrl, null, Map.of(), deadline);
            String markdown = readMarkdownZip(result, deadline);
            log.info("MinerU 4.1.7 解析完成 tenantId={} userId={} tier={} ocrMode={} latencyMs={} requestId={}",
                MDC.get("tenantId"), MDC.get("userId"), tier, settings.getMineruOcrMode(),
                (System.nanoTime() - started) / 1_000_000, RequestIds.current());
            return markdown;
        } catch (ApiException exception) {
            logFailure(exception.getCode());
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logFailure("ATTACHMENT_MINERU4_INTERRUPTED");
            throw new ApiException(HttpStatus.BAD_GATEWAY, "ATTACHMENT_MINERU4_INTERRUPTED", "MinerU 4.1.7 解析已中断");
        } catch (Exception exception) {
            String code = exception instanceof HttpTimeoutException ? "ATTACHMENT_MINERU4_TIMEOUT" : "ATTACHMENT_MINERU4_CALL_FAILED";
            logFailure(code);
            throw new ApiException(HttpStatus.BAD_GATEWAY, code,
                exception instanceof HttpTimeoutException ? "MinerU 4.1.7 解析超时，请检查任务排队和服务状态" : "MinerU 4.1.7 调用失败，请检查服务地址、协议和网络");
        }
    }

    private static JsonNode taskFile(JsonNode task) {
        String parentStatus = requiredText(task, "status");
        if (Set.of("failed", "canceled", "partial").contains(parentStatus)) throw taskFailure();
        if (!Set.of("waiting-file", "pending", "running", "completed").contains(parentStatus)) throw protocol("解析任务状态无效");
        JsonNode files = task.path("files");
        if (!files.isArray() || files.size() != 1 || !files.get(0).isObject()) throw protocol("单文件任务响应必须包含一个文件状态");
        JsonNode file = files.get(0);
        String status = requiredText(file, "status");
        // 不透传供应商 error.message，避免文件正文、部署路径或凭证进入界面和运行日志。
        if (Set.of("failed", "canceled").contains(status) || file.hasNonNull("error")) throw taskFailure();
        if (!Set.of("waiting-file", "pending", "running", "completed").contains(status)) throw protocol("解析文件状态无效");
        if ("completed".equals(parentStatus) && !"completed".equals(status)) throw protocol("任务完成但文件结果不完整");
        return file;
    }

    private static ApiException taskFailure() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "ATTACHMENT_MINERU4_TASK_FAILED", "MinerU 4.1.7 解析任务未成功完成，请检查服务端任务状态");
    }

    /** 只在内存读取 Markdown，不把归档路径写入磁盘；同时限制条目数与解压大小以防压缩炸弹。 */
    static String readMarkdownZip(byte[] bytes, long deadline) throws Exception {
        String fallback = null;
        long expanded = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                remaining(deadline);
                if (++entries > 10000) throw protocol("结果压缩包条目过多");
                String name = entry.getName().replace('\\', '/');
                String baseName = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
                boolean markdown = !entry.isDirectory() && !name.startsWith("__MACOSX/") && baseName.endsWith(".md");
                ByteArrayOutputStream content = markdown ? new ByteArrayOutputStream() : null;
                int count;
                while ((count = zip.read(buffer)) != -1) {
                    remaining(deadline);
                    expanded += count;
                    if (expanded > 64L * 1024 * 1024) throw protocol("结果压缩包解压大小超过上限");
                    if (content != null) {
                        if (content.size() + count > 32 * 1024 * 1024) throw protocol("Markdown 正文超过大小上限");
                        content.write(buffer, 0, count);
                    }
                }
                if (content != null) {
                    String text = content.toString(StandardCharsets.UTF_8);
                    if ("markdown.md".equals(baseName)) {
                        if (text.isBlank()) throw protocol("Markdown 产物为空");
                        return text;
                    }
                    if (fallback == null && !text.isBlank()) fallback = text;
                }
            }
        }
        if (fallback == null) throw protocol("结果压缩包缺少可用 Markdown 正文");
        return fallback;
    }

    /** Tasks 只接受纯文件名；仅规范化提交名称，Agentum 保存的原件名称保持原样。 */
    static String safeFileName(String name) {
        String safe = name == null ? "" : name.replace('\\', '/');
        safe = safe.substring(safe.lastIndexOf('/') + 1).replace(':', '_').replaceAll("\\p{Cc}", "_");
        return safe.isBlank() || Set.of(".", "..").contains(safe) ? "attachment" : safe;
    }

    private JsonNode json(HttpClient client, URI root, String apiKey, String method, String target, Object payload, long deadline) throws Exception {
        byte[] response = request(client, root, apiKey, method, target,
            payload == null ? null : mapper.writeValueAsBytes(payload), Map.of("Content-Type", "application/json"), deadline);
        JsonNode node = mapper.readTree(response);
        if (node == null || !node.isObject() || node.has("error")) throw protocol("服务返回无效 JSON 结果");
        return node;
    }

    private byte[] request(HttpClient client, URI root, String apiKey, String method, String target,
                           byte[] body, Map<String, String> headers, long deadline) throws Exception {
        URI uri = URI.create(root + "/").resolve(target);
        validateUri(uri);
        boolean attachApiKey = sameOrigin(root, uri);
        Map<String, String> currentHeaders = new LinkedHashMap<>(headers);
        for (int redirects = 0; ; redirects++) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(remaining(deadline));
            currentHeaders.forEach(builder::header);
            if (attachApiKey && !apiKey.isBlank() && currentHeaders.keySet().stream().noneMatch(key -> key.equalsIgnoreCase("Authorization"))) builder.setHeader("Authorization", "Bearer " + apiKey);
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
            HttpResponse<byte[]> response = client.send(builder.build(), info -> limitedBody());
            int status = response.statusCode();
            if (Set.of(301, 302, 303, 307, 308).contains(status) && "GET".equals(method) && redirects < 5) {
                URI next = uri.resolve(response.headers().firstValue("Location").orElseThrow(() -> protocol("下载重定向缺少地址")));
                validateUri(next);
                if ("https".equalsIgnoreCase(uri.getScheme()) && "http".equalsIgnoreCase(next.getScheme())) throw protocol("下载地址不允许降级为 HTTP");
                // 逐跳隔离全部上传/鉴权头，跨源后不重新注入 API Key，避免供应商签名地址泄露平台凭证。
                if (!sameOrigin(uri, next)) {
                    currentHeaders.clear();
                    attachApiKey = false;
                }
                uri = next;
                continue;
            }
            if (status < 200 || status >= 300) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "ATTACHMENT_MINERU4_HTTP_FAILED", "MinerU 4.1.7 返回 HTTP " + status);
            }
            return response.body();
        }
    }

    /** 在接收阶段限制响应体，避免异常端点耗尽 Worker 内存；请求超时仍覆盖整个正文接收。 */
    private static HttpResponse.BodySubscriber<byte[]> limitedBody() {
        return new HttpResponse.BodySubscriber<>() {
            private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
            private Flow.Subscription subscription;
            private long size;
            private boolean rejected;

            public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
            public void onSubscribe(Flow.Subscription value) {
                subscription = value;
                delegate.onSubscribe(value);
            }
            public void onNext(List<ByteBuffer> buffers) {
                if (rejected) return;
                for (ByteBuffer buffer : buffers) size += buffer.remaining();
                if (size > 32 * 1024 * 1024) {
                    rejected = true;
                    subscription.cancel();
                    delegate.onError(new java.io.IOException("response limit"));
                    return;
                }
                delegate.onNext(buffers);
            }
            public void onError(Throwable error) { if (!rejected) delegate.onError(error); }
            public void onComplete() { if (!rejected) delegate.onComplete(); }
        };
    }

    static URI serviceRoot(String endpoint) {
        try {
            URI root = URI.create(endpoint == null ? "" : endpoint.trim().replaceAll("/+$", ""));
            validateUri(root);
            if (root.getRawQuery() != null || root.getRawFragment() != null) throw protocol("服务地址不能包含查询或片段");
            return root;
        } catch (IllegalArgumentException exception) {
            throw protocol("服务地址无效");
        }
    }

    private static void validateUri(URI uri) {
        if (uri.getHost() == null || uri.getScheme() == null || uri.getUserInfo() != null
            || !Set.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT))) {
            throw protocol("服务地址无效");
        }
    }

    private static boolean sameOrigin(URI first, URI second) {
        return first.getScheme().equalsIgnoreCase(second.getScheme()) && first.getHost().equalsIgnoreCase(second.getHost())
            && effectivePort(first) == effectivePort(second);
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String encode(String id) {
        return URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) throw protocol("响应缺少有效的 " + field);
        return value.asText();
    }

    private static Duration remaining(long deadline) throws HttpTimeoutException {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new HttpTimeoutException("deadline");
        return Duration.ofNanos(nanos);
    }

    private static ApiException protocol(String message) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "ATTACHMENT_MINERU4_PROTOCOL_INVALID", "MinerU 4.1.7：" + message);
    }

    private static void logFailure(String code) {
        log.warn("MinerU 4.1.7 解析失败 tenantId={} userId={} errorCode={} requestId={}",
            MDC.get("tenantId"), MDC.get("userId"), code, RequestIds.current());
    }
}
