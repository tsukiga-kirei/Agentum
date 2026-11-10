package com.agentum.attachment.application;

import com.agentum.attachment.domain.AttachmentRecognitionSettingEntity;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.RequestIds;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 按官方 4.0 固定版本接入 uploads → parse/jobs → files；4.1 Tasks 属于另一份协议。 */
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
            JsonNode upload = json(client, root, apiKey, "POST", root + "/v1/uploads", Map.of(
                "filename", fileName, "bytes", bytes.length, "purpose", "parse",
                "mime_type", "application/octet-stream",
                "sha256sum", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
            ), deadline);
            String uploadId = requiredText(upload, "id");
            if ("pending".equals(upload.path("status").asText())) {
                String method = upload.path("upload_method").asText("PUT");
                if (!"PUT".equals(method)) throw protocol("上传方法无效");
                Map<String, String> headers = new LinkedHashMap<>();
                JsonNode uploadHeaders = upload.path("upload_headers");
                if (!uploadHeaders.isMissingNode() && !uploadHeaders.isObject()) throw protocol("上传请求头无效");
                uploadHeaders.fields().forEachRemaining(field -> {
                    if (!field.getValue().isTextual()) throw protocol("上传请求头无效");
                    headers.put(field.getKey(), field.getValue().asText());
                });
                request(client, root, apiKey, method, requiredText(upload, "upload_url"), bytes, headers, deadline);
                upload = json(client, root, apiKey, "POST", root + "/v1/uploads/" + encode(uploadId) + "/complete", null, deadline);
            }
            if (!"completed".equals(upload.path("status").asText())) throw protocol("上传未完成");
            String fileId = requiredText(upload.path("file"), "id");
            String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            // 原生文档按官方约束整本走 Flash，PDF/图片才使用管理员选择的质量档位。
            String tier = NATIVE_EXTENSIONS.contains(extension) ? "flash" : settings.getMineruTier();
            JsonNode job = json(client, root, apiKey, "POST", root + "/v1/parse/jobs", Map.of(
                "files", List.of(Map.of("source", Map.of("type", "file_id", "file_id", fileId))),
                "tier", tier, "ocr_mode", settings.getMineruOcrMode(), "output_formats", List.of("markdown")
            ), deadline);
            String jobId = requiredText(job, "job_id");
            for (int polls = 0; ; polls++) {
                String status = requiredText(job, "status");
                if ("completed".equals(status)) break;
                if (Set.of("failed", "canceled", "partial").contains(status)) {
                    // 供应商错误可能包含正文、路径或凭证；只回传稳定终态，不透传原始 message。
                    throw new ApiException(HttpStatus.BAD_GATEWAY, "ATTACHMENT_MINERU4_JOB_FAILED", "MinerU 4.0 解析任务未成功完成（" + status + "）");
                }
                if (!Set.of("queued", "running").contains(status)) throw protocol("解析任务状态无效");
                if (polls >= 3600) throw new HttpTimeoutException("poll limit");
                Duration remaining = remaining(deadline);
                Thread.sleep(Math.min(1000, Math.max(1, remaining.toMillis())));
                job = json(client, root, apiKey, "GET", root + "/v1/parse/jobs/" + encode(jobId), null, deadline);
                if (!jobId.equals(requiredText(job, "job_id"))) throw protocol("解析任务标识不匹配");
            }
            JsonNode files = job.path("files");
            if (!files.isArray() || files.size() != 1 || !"completed".equals(files.get(0).path("status").asText())) {
                throw protocol("任务完成但文件结果不完整");
            }
            String markdownId = requiredText(files.get(0).path("output_files").path("markdown"), "file_id");
            String markdown = new String(request(client, root, apiKey, "GET",
                root + "/v1/files/" + encode(markdownId) + "/content", null, Map.of(), deadline), StandardCharsets.UTF_8);
            if (markdown.isBlank()) throw protocol("Markdown 产物为空");
            log.info("MinerU 4.0 解析完成 tenantId={} userId={} tier={} ocrMode={} latencyMs={} requestId={}",
                MDC.get("tenantId"), MDC.get("userId"), tier, settings.getMineruOcrMode(),
                (System.nanoTime() - started) / 1_000_000, RequestIds.current());
            return markdown;
        } catch (ApiException exception) {
            logFailure(exception.getCode());
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logFailure("ATTACHMENT_MINERU4_INTERRUPTED");
            throw new ApiException(HttpStatus.BAD_GATEWAY, "ATTACHMENT_MINERU4_INTERRUPTED", "MinerU 4.0 解析已中断");
        } catch (Exception exception) {
            String code = exception instanceof HttpTimeoutException ? "ATTACHMENT_MINERU4_TIMEOUT" : "ATTACHMENT_MINERU4_CALL_FAILED";
            logFailure(code);
            throw new ApiException(HttpStatus.BAD_GATEWAY, code,
                exception instanceof HttpTimeoutException ? "MinerU 4.0 解析超时，请检查任务排队和服务状态" : "MinerU 4.0 调用失败，请检查服务地址、协议和网络");
        }
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
        URI uri = target.startsWith("/v1/") ? URI.create(root + target) : URI.create(root + "/").resolve(target);
        validateUri(uri);
        boolean attachApiKey = sameOrigin(root, uri);
        Map<String, String> currentHeaders = new LinkedHashMap<>(headers);
        for (int redirects = 0; ; redirects++) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(remaining(deadline));
            currentHeaders.forEach(builder::header);
            if (attachApiKey && !apiKey.isBlank()) builder.setHeader("Authorization", "Bearer " + apiKey);
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
                throw new ApiException(HttpStatus.BAD_GATEWAY, "ATTACHMENT_MINERU4_HTTP_FAILED", "MinerU 4.0 返回 HTTP " + status);
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
        return new ApiException(HttpStatus.BAD_GATEWAY, "ATTACHMENT_MINERU4_PROTOCOL_INVALID", "MinerU 4.0：" + message);
    }

    private static void logFailure(String code) {
        log.warn("MinerU 4.0 解析失败 tenantId={} userId={} errorCode={} requestId={}",
            MDC.get("tenantId"), MDC.get("userId"), code, RequestIds.current());
    }
}
