package com.agentum.attachment.application;

import com.agentum.attachment.domain.AttachmentRecognitionSettingEntity;
import com.agentum.attachment.infrastructure.AttachmentRecognitionSettingRepository;
import com.agentum.attachment.interfaces.AttachmentRecognitionApi;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.RequestIds;
import com.agentum.shared.security.FieldEncryptionService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AttachmentRecognitionSettingsService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentRecognitionSettingsService.class);
    private static final short SETTINGS_ID = 1;
    private static final Set<String> ENGINES = Set.of("local", "mineru");
    private static final Set<String> PARSE_METHODS = Set.of("auto", "txt", "ocr");
    private static final Set<String> MINERU_BACKENDS = Set.of(
        "pipeline", "vlm-auto-engine", "vlm-http-client", "hybrid-auto-engine", "hybrid-http-client"
    );
    private static final Set<String> RETENTION_POLICIES = Set.of("permanent", "days");
    private static final Pattern EXTENSION = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,19}$");

    private final AttachmentRecognitionSettingRepository repository;
    private final FieldEncryptionService encryptionService;
    private final Clock clock;

    public AttachmentRecognitionSettingsService(
        AttachmentRecognitionSettingRepository repository,
        FieldEncryptionService encryptionService,
        Clock clock
    ) {
        this.repository = repository;
        this.encryptionService = encryptionService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AttachmentRecognitionSettingEntity requireEntity() {
        return repository.findById(SETTINGS_ID)
            .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "ATTACHMENT_SETTINGS_MISSING", "附件识别系统配置不存在"));
    }

    @Transactional(readOnly = true)
    public AttachmentRecognitionApi.Settings getSettings() {
        return toSettings(requireEntity());
    }

    @Transactional
    public AttachmentRecognitionApi.Settings update(AttachmentRecognitionApi.UpdateSettingsRequest request, UUID operatorUserId) {
        String engine = normalize(request.recognitionEngine());
        if (!ENGINES.contains(engine)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_ENGINE_INVALID", "识别方式只能选择简单识别或复杂识别");
        }
        String parseMethod = normalize(request.mineruParseMethod());
        if (!PARSE_METHODS.contains(parseMethod)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_MINERU_PARSE_METHOD_INVALID", "MinerU 解析方式只能选择自动识别、文本解析或 OCR 识别");
        }
        String backend = normalize(request.mineruBackend());
        if (!MINERU_BACKENDS.contains(backend)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_MINERU_BACKEND_INVALID", "MinerU Backend 选项无效");
        }
        String retentionPolicy = normalize(request.retentionPolicy());
        if (!RETENTION_POLICIES.contains(retentionPolicy)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_RETENTION_POLICY_INVALID", "文件默认保存时间只能选择永久或按天保存");
        }
        String endpoint = trimToNull(request.mineruEndpoint());
        if ("mineru".equals(engine) && endpoint == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_MINERU_ENDPOINT_REQUIRED", "复杂识别必须配置 MinerU 端点");
        }
        List<String> extensions = normalizeExtensions(request.mineruSupportedExtensions());
        AttachmentRecognitionSettingEntity entity = requireEntity();
        String encryptedKey = entity.getEncryptedMineruApiKey();
        if (request.clearMineruApiKey()) {
            encryptedKey = null;
        } else if (request.mineruApiKey() != null && !request.mineruApiKey().isBlank()) {
            encryptedKey = encryptionService.encrypt(request.mineruApiKey().trim());
        }
        entity.update(
            request.recognitionEnabled(), engine, request.maxFileSizeMb(), request.maxFilesPerField(),
            request.maxExtractedChars(), retentionPolicy, request.retentionDays(), extensions, endpoint, encryptedKey,
            backend, parseMethod, request.mineruLanguage().trim(),
            request.mineruEnableFormula(), request.mineruEnableTable(), request.mineruConnectTimeoutSeconds(),
            request.mineruReadTimeoutSeconds(), clock.instant()
        );
        repository.save(entity);
        log.info(
            "附件识别系统配置更新成功 userId={} engine={} enabled={} retentionPolicy={} extensionCount={} requestId={}",
            operatorUserId, engine, request.recognitionEnabled(), retentionPolicy, extensions.size(), RequestIds.current()
        );
        return toSettings(entity);
    }

    public AttachmentRecognitionApi.TestConnectionResult testConnection(AttachmentRecognitionApi.TestConnectionRequest request, UUID operatorUserId) {
        String endpoint = request.mineruEndpoint().trim().replaceAll("/+$", "");
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(request.connectTimeoutSeconds())).build();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint + "/health"))
            .timeout(Duration.ofSeconds(request.connectTimeoutSeconds()))
            .GET();
        String apiKey = request.mineruApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey.trim());
        } else if (request.useSavedApiKey()) {
            AttachmentRecognitionSettingEntity entity = requireEntity();
            if (entity.getEncryptedMineruApiKey() != null && !entity.getEncryptedMineruApiKey().isBlank()) {
                builder.header("Authorization", "Bearer " + encryptionService.decrypt(entity.getEncryptedMineruApiKey()));
            }
        }
        long started = System.nanoTime();
        try {
            HttpResponse<Void> response = client.send(builder.build(), HttpResponse.BodyHandlers.discarding());
            long latency = (System.nanoTime() - started) / 1_000_000;
            if (response.statusCode() >= 200 && response.statusCode() < 400) {
                log.info(
                    "MinerU 连接测试成功 userId={} status={} latencyMs={} requestId={}",
                    operatorUserId, response.statusCode(), latency, RequestIds.current()
                );
                return new AttachmentRecognitionApi.TestConnectionResult("success", "MinerU 服务可达", latency);
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, "ATTACHMENT_MINERU_TEST_FAILED", "MinerU 健康检查返回 HTTP " + response.statusCode());
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            // 日志不记录端点中的路径和查询参数，避免意外泄露供应商部署信息或临时凭证。
            log.warn("MinerU 连接测试失败 userId={} requestId={}", operatorUserId, RequestIds.current(), exception);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "ATTACHMENT_MINERU_TEST_FAILED", "MinerU 服务不可达，请检查地址和网络");
        }
    }

    public String decryptMineruApiKey(AttachmentRecognitionSettingEntity entity) {
        String encrypted = entity.getEncryptedMineruApiKey();
        return encrypted == null || encrypted.isBlank() ? "" : encryptionService.decrypt(encrypted);
    }

    private AttachmentRecognitionApi.Settings toSettings(AttachmentRecognitionSettingEntity entity) {
        return new AttachmentRecognitionApi.Settings(
            entity.isRecognitionEnabled(), entity.getRecognitionEngine(), entity.getMaxFileSizeMb(),
            entity.getMaxFilesPerField(), entity.getMaxExtractedChars(), entity.getRetentionPolicy(), entity.getRetentionDays(),
            entity.getMineruSupportedExtensions(), entity.getMineruEndpoint(),
            entity.getEncryptedMineruApiKey() != null && !entity.getEncryptedMineruApiKey().isBlank(),
            entity.getMineruBackend(), entity.getMineruParseMethod(), entity.getMineruLanguage(),
            entity.isMineruEnableFormula(), entity.isMineruEnableTable(), entity.getMineruConnectTimeoutSeconds(),
            entity.getMineruReadTimeoutSeconds(), entity.getUpdatedAt()
        );
    }

    private static List<String> normalizeExtensions(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String raw : values) {
            String extension = normalize(raw).replaceFirst("^\\.", "");
            if (!EXTENSION.matcher(extension).matches()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_EXTENSION_INVALID", "扩展名只能包含小写字母、数字、下划线或短横线");
            }
            normalized.add(extension);
        }
        if (normalized.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_EXTENSION_REQUIRED", "至少配置一个复杂识别扩展名");
        }
        return List.copyOf(normalized);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
