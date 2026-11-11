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
    private static final Set<String> ENGINES = Set.of("local", "mineru", "hybrid");
    private static final Set<String> MINERU_PARSERS = Set.of("mineru", "mineru4");
    private static final Set<String> TIERS = Set.of("flash", "basic", "standard", "advanced");
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

    @Transactional(readOnly = true)
    public AttachmentRecognitionApi.Capabilities getCapabilities() {
        AttachmentRecognitionSettingEntity entity = requireEntity();
        return new AttachmentRecognitionApi.Capabilities(
            entity.isRecognitionEnabled(),
            entity.getRecognitionEngine(),
            AttachmentRecognitionPolicy.supportedExtensions(entity),
            AttachmentRecognitionPolicy.blockedExtensions()
        );
    }

    @Transactional
    public AttachmentRecognitionApi.Settings update(AttachmentRecognitionApi.UpdateSettingsRequest request, UUID operatorUserId) {
        String engine = normalize(request.recognitionEngine());
        if (!ENGINES.contains(engine)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_ENGINE_INVALID", "识别方式只能选择简单识别、复杂识别或混合识别");
        }
        String parser = normalize(request.mineruParser());
        validateParser(parser);
        String tier = normalize(request.mineruTier());
        if (!TIERS.contains(tier)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_MINERU_TIER_INVALID", "解析档位只能选择快速、基础、标准或高精度");
        }
        String ocrMode = normalize(request.mineruOcrMode());
        if (!PARSE_METHODS.contains(ocrMode)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_MINERU_OCR_MODE_INVALID", "OCR 模式只能选择自动、文本或强制 OCR");
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
        if (!"local".equals(engine) && endpoint == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_MINERU_ENDPOINT_REQUIRED", "复杂识别和混合识别必须配置 MinerU 端点");
        }
        if (endpoint != null) validateEndpoint(endpoint);
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
        entity.updateMineruParser(parser, tier, ocrMode);
        repository.save(entity);
        log.info(
            "附件识别系统配置更新成功 userId={} engine={} parser={} tier={} ocrMode={} enabled={} retentionPolicy={} extensionCount={} requestId={}",
            operatorUserId, engine, parser, tier, ocrMode, request.recognitionEnabled(), retentionPolicy, extensions.size(), RequestIds.current()
        );
        return toSettings(entity);
    }

    public AttachmentRecognitionApi.TestConnectionResult testConnection(AttachmentRecognitionApi.TestConnectionRequest request, UUID operatorUserId) {
        String parser = normalize(request.mineruParser());
        validateParser(parser);
        validateEndpoint(request.mineruEndpoint());
        String endpoint = request.mineruEndpoint().trim().replaceAll("/+$", "");
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(request.connectTimeoutSeconds())).build();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint + ("mineru4".equals(parser) ? "/v1/health" : "/health")))
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
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            long latency = (System.nanoTime() - started) / 1_000_000;
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                if ("mineru4".equals(parser)) {
                    var health = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body());
                    if (health == null || !"ok".equals(health.path("status").asText())) {
                        throw new ApiException(HttpStatus.BAD_GATEWAY, "ATTACHMENT_MINERU_TEST_FAILED", "MinerU 4.1.7 健康状态异常");
                    }
                    // 4.0 与 4.1 使用相同健康地址，单凭 HTTP 成功不能证明 Tasks 协议可用。
                    String version = health.path("version").asText("").trim();
                    if (version.matches("4\\.0(?:\\..*)?")) {
                        throw new ApiException(HttpStatus.BAD_GATEWAY, "ATTACHMENT_MINERU_VERSION_UNSUPPORTED",
                            "检测到 MinerU 4.0 服务，请使用 4.1.7 服务端及 Tasks 接口");
                    }
                }
                log.info(
                    "MinerU 连接测试成功 userId={} status={} latencyMs={} requestId={}",
                    operatorUserId, response.statusCode(), latency, RequestIds.current()
                );
                return new AttachmentRecognitionApi.TestConnectionResult("success", "mineru4".equals(parser) ? "MinerU 4.1.7 服务可达" : "MinerU 旧版服务可达", latency);
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, "ATTACHMENT_MINERU_TEST_FAILED", "MinerU 健康检查返回 HTTP " + response.statusCode());
        } catch (ApiException exception) {
            log.warn("MinerU 连接测试失败 userId={} parser={} errorCode={} requestId={}", operatorUserId, parser, exception.getCode(), RequestIds.current());
            throw exception;
        } catch (Exception exception) {
            // 日志不记录端点中的路径和查询参数，避免意外泄露供应商部署信息或临时凭证。
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("MinerU 连接测试失败 userId={} parser={} requestId={}", operatorUserId, parser, RequestIds.current());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "ATTACHMENT_MINERU_TEST_FAILED", "MinerU 服务不可达，请检查地址和网络");
        }
    }

    public String decryptMineruApiKey(AttachmentRecognitionSettingEntity entity) {
        String encrypted = entity.getEncryptedMineruApiKey();
        return encrypted == null || encrypted.isBlank() ? "" : encryptionService.decrypt(encrypted);
    }

    private AttachmentRecognitionApi.Settings toSettings(AttachmentRecognitionSettingEntity entity) {
        return new AttachmentRecognitionApi.Settings(
            entity.isRecognitionEnabled(), entity.getRecognitionEngine(), AttachmentRecognitionPolicy.localSupportedExtensions(), entity.getMaxFileSizeMb(),
            entity.getMaxFilesPerField(), entity.getMaxExtractedChars(), entity.getRetentionPolicy(), entity.getRetentionDays(),
            entity.getMineruSupportedExtensions(), entity.getMineruEndpoint(),
            entity.getEncryptedMineruApiKey() != null && !entity.getEncryptedMineruApiKey().isBlank(),
            entity.getMineruParser(), entity.getMineruTier(), entity.getMineruOcrMode(),
            entity.getMineruBackend(), entity.getMineruParseMethod(), entity.getMineruLanguage(),
            entity.isMineruEnableFormula(), entity.isMineruEnableTable(), entity.getMineruConnectTimeoutSeconds(),
            entity.getMineruReadTimeoutSeconds(), entity.getUpdatedAt()
        );
    }

    private static void validateParser(String parser) {
        if (!MINERU_PARSERS.contains(parser)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_MINERU_PARSER_INVALID", "请选择 MinerU 旧版或 MinerU 4.1.7 解析器");
        }
    }

    private static void validateEndpoint(String endpoint) {
        try {
            Mineru4AttachmentParser.serviceRoot(endpoint);
        } catch (ApiException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_MINERU_ENDPOINT_INVALID", "MinerU 服务地址必须是有效的 HTTP/HTTPS 根地址，不能包含账号、查询参数或片段");
        }
    }

    private static List<String> normalizeExtensions(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String raw : values) {
            String extension = normalize(raw).replaceFirst("^\\.", "");
            if (!EXTENSION.matcher(extension).matches()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_EXTENSION_INVALID", "扩展名只能包含小写字母、数字、下划线或短横线");
            }
            if (AttachmentRecognitionPolicy.blockedExtensions().contains(extension)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_EXTENSION_BLOCKED", "识别白名单不能包含平台禁止的危险扩展名");
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
