package com.agentum.attachment.application;

import com.agentum.attachment.domain.AttachmentParseResultEntity;
import com.agentum.attachment.domain.AttachmentRecognitionSettingEntity;
import com.agentum.attachment.domain.InputAttachmentEntity;
import com.agentum.attachment.infrastructure.AttachmentParseResultRepository;
import com.agentum.attachment.infrastructure.InputAttachmentRepository;
import com.agentum.attachment.interfaces.AttachmentApi;
import com.agentum.attachment.messaging.AttachmentParseCommand;
import com.agentum.attachment.messaging.AttachmentParseCommandPublisher;
import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.audit.application.AuditService;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.RequestIds;
import com.agentum.workbench.application.WorkbenchAccess;
import com.agentum.workflow.domain.WorkflowNodeRunEntity;
import com.agentum.workflow.domain.WorkflowRunEntity;
import com.agentum.workflow.domain.WorkflowWaitingEventEntity;
import com.agentum.workflow.infrastructure.WorkflowNodeRunRepository;
import com.agentum.workflow.infrastructure.WorkflowRunRepository;
import com.agentum.workflow.infrastructure.WorkflowWaitingEventRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class InputAttachmentService {

    private static final Logger log = LoggerFactory.getLogger(InputAttachmentService.class);
    private static final Set<String> LOCAL_EXTENSIONS = Set.of("txt", "md", "csv", "pdf", "doc", "docx", "xls", "xlsx");
    private static final Set<String> BLOCKED_EXTENSIONS = Set.of(
        "app", "bat", "cmd", "com", "dll", "dmg", "exe", "hta", "jar", "js", "msi", "ps1", "scr", "sh", "vbs"
    );
    private static final Pattern SAFE_FILE_NAME = Pattern.compile("[^\\p{L}\\p{N}._()（） -]");

    private final WorkbenchAccess workbenchAccess;
    private final WorkflowRunRepository runRepository;
    private final WorkflowNodeRunRepository nodeRunRepository;
    private final WorkflowWaitingEventRepository waitingEventRepository;
    private final InputAttachmentRepository attachmentRepository;
    private final AttachmentParseResultRepository parseResultRepository;
    private final AttachmentRecognitionSettingsService settingsService;
    private final AttachmentObjectStorage storage;
    private final AttachmentParseCommandPublisher commandPublisher;
    private final Clock clock;
    private final AuditService auditService;

    public InputAttachmentService(
        WorkbenchAccess workbenchAccess,
        WorkflowRunRepository runRepository,
        WorkflowNodeRunRepository nodeRunRepository,
        WorkflowWaitingEventRepository waitingEventRepository,
        InputAttachmentRepository attachmentRepository,
        AttachmentParseResultRepository parseResultRepository,
        AttachmentRecognitionSettingsService settingsService,
        AttachmentObjectStorage storage,
        AttachmentParseCommandPublisher commandPublisher,
        Clock clock,
        AuditService auditService
    ) {
        this.workbenchAccess = workbenchAccess;
        this.runRepository = runRepository;
        this.nodeRunRepository = nodeRunRepository;
        this.waitingEventRepository = waitingEventRepository;
        this.attachmentRepository = attachmentRepository;
        this.parseResultRepository = parseResultRepository;
        this.settingsService = settingsService;
        this.storage = storage;
        this.commandPublisher = commandPublisher;
        this.clock = clock;
        this.auditService = auditService;
    }

    public AttachmentApi.AttachmentRow upload(
        UUID tenantId,
        UUID runId,
        UUID nodeRunId,
        CurrentUserPrincipal principal,
        String fieldId,
        MultipartFile file
    ) {
        RuntimeContext context = requireContext(tenantId, runId, nodeRunId, principal, true);
        Map<String, Object> field = requireFileField(context.nodeRun(), fieldId);
        AttachmentRecognitionSettingEntity settings = settingsService.requireEntity();
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_FILE_REQUIRED", "请选择需要上传的附件");
        }
        String fileName = normalizeFileName(file.getOriginalFilename());
        String extension = extensionOf(fileName);
        validateExtension(field, settings, extension);

        int fieldMaxSize = positiveInt(field.get("maxFileSizeMb"), settings.getMaxFileSizeMb());
        int maxSizeMb = Math.min(fieldMaxSize, settings.getMaxFileSizeMb());
        if (file.getSize() <= 0 || file.getSize() > maxSizeMb * 1024L * 1024L) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "ATTACHMENT_SIZE_INVALID", "附件不能为空，且单个文件不能超过 " + maxSizeMb + " MB");
        }
        int fieldMaxFiles = positiveInt(field.get("maxFiles"), settings.getMaxFilesPerField());
        int maxFiles = Math.min(fieldMaxFiles, settings.getMaxFilesPerField());
        long currentCount = attachmentRepository.countByTenantIdAndRunIdAndNodeRunIdAndFieldId(tenantId, runId, nodeRunId, fieldId);
        if (currentCount >= maxFiles) {
            throw new ApiException(HttpStatus.CONFLICT, "ATTACHMENT_COUNT_EXCEEDED", "该字段最多上传 " + maxFiles + " 个附件");
        }
        // 先使用 Multipart 元数据完成容量与数量拦截，再读取正文，避免超大文件提前占用堆内存。
        byte[] bytes = readBytes(file);
        validateFileSignature(extension, bytes);

        // 附件字段上传后统一服从系统识别配置，节点不能通过历史草稿字段绕过平台级识别策略。
        boolean recognitionEnabled = settings.isRecognitionEnabled();
        String engine = recognitionEnabled ? settings.getRecognitionEngine() : "none";
        String initialStatus = recognitionEnabled ? "queued" : "ready";
        UUID attachmentId = UUID.randomUUID();
        String contentType = normalizeContentType(extension);
        String storageKey = storage.storeOriginal(tenantId, attachmentId, fileName, contentType, bytes);
        Instant now = clock.instant();
        InputAttachmentEntity attachment = InputAttachmentEntity.create(
            attachmentId,
            tenantId,
            runId,
            nodeRunId,
            fieldId,
            stringValue(field.get("variable")),
            principal.userId(),
            fileName,
            extension,
            contentType,
            bytes.length,
            sha256(bytes),
            storageKey,
            engine,
            initialStatus,
            "permanent".equals(settings.getRetentionPolicy()) ? null : now.plus(settings.getRetentionDays(), ChronoUnit.DAYS),
            now
        );
        try {
            attachmentRepository.saveAndFlush(attachment);
        } catch (RuntimeException exception) {
            storage.delete(storageKey);
            throw exception;
        }
        if (recognitionEnabled) {
            try {
                commandPublisher.publish(new AttachmentParseCommand(attachmentId, RequestIds.current()));
            } catch (RuntimeException exception) {
                // 消息中间件不可用时保留已安全保存的原件，并把状态明确暴露给用户，避免产生“数据库有记录但对象已被误删”的断链数据。
                attachment.markFailed("ATTACHMENT_QUEUE_FAILED", "附件已保存，但识别任务入队失败，请删除后重试", clock.instant());
                attachmentRepository.save(attachment);
                log.error(
                    "附件识别任务入队失败 tenantId={} runId={} nodeRunId={} attachmentId={} userId={} requestId={}",
                    tenantId, runId, nodeRunId, attachmentId, principal.userId(), RequestIds.current(), exception
                );
            }
        }
        log.info(
            "输入附件上传成功 tenantId={} runId={} nodeRunId={} attachmentId={} userId={} fieldId={} extension={} sizeBytes={} engine={} requestId={}",
            tenantId, runId, nodeRunId, attachmentId, principal.userId(), fieldId, extension, bytes.length, engine, RequestIds.current()
        );
        auditService.recordOperationLog(
            tenantId, principal.userId(), principal.username(), "UPLOAD_INPUT_ATTACHMENT", "INPUT_ATTACHMENT",
            attachmentId.toString(), fileName, "上传输入节点附件。",
            Map.of("runId", runId.toString(), "nodeRunId", nodeRunId.toString(), "fieldId", fieldId, "extension", extension, "sizeBytes", bytes.length, "recognitionEngine", engine),
            null
        );
        return toRow(attachment, null);
    }

    public AttachmentApi.AttachmentList list(
        UUID tenantId,
        UUID runId,
        UUID nodeRunId,
        CurrentUserPrincipal principal,
        String fieldId
    ) {
        requireContext(tenantId, runId, nodeRunId, principal, false);
        List<InputAttachmentEntity> attachments = attachmentRepository
            .findByTenantIdAndRunIdAndNodeRunIdAndFieldIdOrderByCreatedAtAsc(tenantId, runId, nodeRunId, fieldId);
        assertCanReadAll(principal, attachments);
        Map<UUID, AttachmentParseResultEntity> results = new LinkedHashMap<>();
        parseResultRepository.findByAttachmentIdIn(attachments.stream().map(InputAttachmentEntity::getId).toList())
            .forEach(result -> results.put(result.getAttachmentId(), result));
        return new AttachmentApi.AttachmentList(attachments.stream().map(item -> toRow(item, results.get(item.getId()))).toList());
    }

    public AttachmentFileAccess download(
        UUID tenantId,
        UUID runId,
        UUID nodeRunId,
        UUID attachmentId,
        CurrentUserPrincipal principal
    ) {
        requireContext(tenantId, runId, nodeRunId, principal, false);
        InputAttachmentEntity attachment = requireAttachment(tenantId, runId, nodeRunId, attachmentId);
        assertCanRead(principal, attachment);
        byte[] originalBytes = storage.read(attachment.getStorageKey());
        log.info(
            "输入附件原件读取成功 tenantId={} runId={} nodeRunId={} attachmentId={} userId={} requestId={}",
            tenantId, runId, nodeRunId, attachmentId, principal.userId(), RequestIds.current()
        );
        auditService.recordOperationLog(
            tenantId, principal.userId(), principal.username(), "DOWNLOAD_INPUT_ATTACHMENT", "INPUT_ATTACHMENT",
            attachmentId.toString(), attachment.getOriginalFileName(), "读取输入节点附件原件。",
            Map.of("runId", runId.toString(), "nodeRunId", nodeRunId.toString()), null
        );
        return new AttachmentFileAccess(originalBytes, attachment.getOriginalFileName(), attachment.getContentType());
    }

    public AttachmentFileAccess parsedPreview(
        UUID tenantId,
        UUID runId,
        UUID nodeRunId,
        UUID attachmentId,
        CurrentUserPrincipal principal
    ) {
        requireContext(tenantId, runId, nodeRunId, principal, false);
        InputAttachmentEntity attachment = requireAttachment(tenantId, runId, nodeRunId, attachmentId);
        assertCanRead(principal, attachment);
        AttachmentParseResultEntity result = parseResultRepository.findByAttachmentId(attachmentId)
            .filter(item -> "ready".equals(item.getStatus()) && item.getContentStorageKey() != null)
            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "ATTACHMENT_PREVIEW_NOT_READY", "附件识别尚未完成，暂时无法查看内容预览"));
        byte[] parsedBytes = storage.read(result.getContentStorageKey());
        auditService.recordOperationLog(
            tenantId, principal.userId(), principal.username(), "PREVIEW_INPUT_ATTACHMENT", "INPUT_ATTACHMENT",
            attachmentId.toString(), attachment.getOriginalFileName(), "读取输入附件识别正文用于预览。",
            Map.of("runId", runId.toString(), "nodeRunId", nodeRunId.toString(), "parseResultId", result.getId().toString()), null
        );
        return new AttachmentFileAccess(
            parsedBytes,
            attachment.getOriginalFileName() + ".md",
            "text/markdown; charset=UTF-8"
        );
    }

    public void delete(
        UUID tenantId,
        UUID runId,
        UUID nodeRunId,
        UUID attachmentId,
        CurrentUserPrincipal principal
    ) {
        requireContext(tenantId, runId, nodeRunId, principal, true);
        InputAttachmentEntity attachment = requireAttachment(tenantId, runId, nodeRunId, attachmentId);
        if (!isTenantManager(principal) && !principal.userId().equals(attachment.getUploadedBy())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ATTACHMENT_DELETE_FORBIDDEN", "当前账号不能删除该附件");
        }
        parseResultRepository.findByAttachmentId(attachmentId).ifPresent(result -> {
            if (result.getContentStorageKey() != null) {
                storage.delete(result.getContentStorageKey());
            }
        });
        storage.delete(attachment.getStorageKey());
        attachmentRepository.delete(attachment);
        log.info(
            "输入附件删除成功 tenantId={} runId={} nodeRunId={} attachmentId={} userId={} requestId={}",
            tenantId, runId, nodeRunId, attachmentId, principal.userId(), RequestIds.current()
        );
        auditService.recordOperationLog(
            tenantId, principal.userId(), principal.username(), "DELETE_INPUT_ATTACHMENT", "INPUT_ATTACHMENT",
            attachmentId.toString(), attachment.getOriginalFileName(), "删除尚未提交的输入节点附件。",
            Map.of("runId", runId.toString(), "nodeRunId", nodeRunId.toString(), "fieldId", attachment.getFieldId()), null
        );
    }

    /**
     * 提交输入节点时只接受本节点已登记的附件 ID，并把客户端数组转换为不可伪造的附件引用。
     * 复杂识别没有本地回退；标记为识别必需的字段必须等待 MinerU 成功后才能继续流程。
     */
    public Map<String, Object> resolveSubmittedPayload(
        UUID tenantId,
        CurrentUserPrincipal principal,
        WorkflowNodeRunEntity nodeRun,
        Map<String, Object> payload
    ) {
        Map<String, Object> resolved = new LinkedHashMap<>(payload == null ? Map.of() : payload);
        for (Map<String, Object> field : inputFields(nodeRun)) {
            if (!"file".equals(stringValue(field.get("fieldType")))) {
                continue;
            }
            String variable = stringValue(field.get("variable"));
            String fieldId = stringValue(field.get("id"));
            List<UUID> ids = parseAttachmentIds(resolved.get(variable));
            if (ids.isEmpty()) {
                resolved.put(variable, List.of());
                continue;
            }
            List<InputAttachmentEntity> attachments = attachmentRepository.findByIdIn(ids);
            if (attachments.size() != ids.size()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_REFERENCE_INVALID", "提交内容包含不存在的附件");
            }
            Map<UUID, AttachmentParseResultEntity> results = new LinkedHashMap<>();
            parseResultRepository.findByAttachmentIdIn(ids).forEach(result -> results.put(result.getAttachmentId(), result));
            List<Map<String, Object>> references = new ArrayList<>();
            for (UUID id : ids) {
                InputAttachmentEntity attachment = attachments.stream().filter(item -> item.getId().equals(id)).findFirst().orElseThrow();
                if (!tenantId.equals(attachment.getTenantId()) || !nodeRun.getRunId().equals(attachment.getRunId())
                    || !nodeRun.getId().equals(attachment.getNodeRunId()) || !fieldId.equals(attachment.getFieldId())) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_REFERENCE_INVALID", "附件与当前输入字段不匹配");
                }
                assertCanRead(principal, attachment);
                if (Set.of("queued", "parsing").contains(attachment.getStatus())) {
                    throw new ApiException(HttpStatus.CONFLICT, "ATTACHMENT_PARSE_PENDING", "附件仍在识别中，请稍后再提交");
                }
                if (Boolean.TRUE.equals(field.get("recognitionRequired")) && !"ready".equals(attachment.getStatus())) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ATTACHMENT_PARSE_REQUIRED", "附件识别失败，当前字段要求识别成功后才能提交");
                }
                AttachmentParseResultEntity result = results.get(id);
                Map<String, Object> reference = new LinkedHashMap<>();
                reference.put("attachmentId", attachment.getId().toString());
                reference.put("fileName", attachment.getOriginalFileName());
                reference.put("extension", attachment.getExtension());
                reference.put("contentType", attachment.getContentType());
                reference.put("sizeBytes", attachment.getSizeBytes());
                reference.put("status", attachment.getStatus());
                if (result != null && "ready".equals(result.getStatus())) {
                    reference.put("parseResultId", result.getId().toString());
                    reference.put("truncated", result.isTruncated());
                }
                references.add(reference);
            }
            resolved.put(variable, references);
        }
        return resolved;
    }

    private RuntimeContext requireContext(
        UUID tenantId,
        UUID runId,
        UUID nodeRunId,
        CurrentUserPrincipal principal,
        boolean requireOpenTodo
    ) {
        workbenchAccess.assertCanAccessWorkbench(principal, tenantId);
        WorkflowRunEntity run = runRepository.findByIdAndTenantId(runId, tenantId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WORKBENCH_RUN_NOT_FOUND", "任务运行不存在"));
        WorkflowNodeRunEntity nodeRun = nodeRunRepository.findByIdAndRunId(nodeRunId, runId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WORKBENCH_NODE_RUN_NOT_FOUND", "节点运行不存在"));
        if (!"user_input".equals(nodeRun.getNodeType())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_NODE_INVALID", "只有输入节点可以管理附件");
        }
        if (requireOpenTodo) {
            WorkflowWaitingEventEntity todo = waitingEventRepository.findByRunIdAndStatusOrderByCreatedAtDesc(runId, "open").stream()
                .filter(item -> nodeRunId.equals(item.getNodeRunId()))
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "ATTACHMENT_TODO_CLOSED", "当前输入节点已不可修改附件"));
            if (!isTenantManager(principal)
                && (!"user".equals(todo.getWaitingForType()) || !principal.userId().equals(todo.getWaitingForId()))) {
                throw new ApiException(HttpStatus.FORBIDDEN, "ATTACHMENT_WRITE_FORBIDDEN", "当前账号不能修改该输入节点的附件");
            }
        }
        return new RuntimeContext(run, nodeRun);
    }

    private static Map<String, Object> requireFileField(WorkflowNodeRunEntity nodeRun, String fieldId) {
        return inputFields(nodeRun).stream()
            .filter(field -> fieldId != null && fieldId.equals(stringValue(field.get("id"))))
            .filter(field -> "file".equals(stringValue(field.get("fieldType"))))
            .findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_FIELD_INVALID", "附件字段不存在或类型不正确"));
    }

    private static List<Map<String, Object>> inputFields(WorkflowNodeRunEntity nodeRun) {
        Object raw = nodeRun.getConfigSnapshot().get("inputFields");
        if (!(raw instanceof List<?> fields)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : fields) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> normalized = new LinkedHashMap<>();
                map.forEach((key, value) -> normalized.put(String.valueOf(key), value));
                result.add(normalized);
            }
        }
        return result;
    }

    private static void validateExtension(
        Map<String, Object> field,
        AttachmentRecognitionSettingEntity settings,
        String extension
    ) {
        if (BLOCKED_EXTENSIONS.contains(extension)) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "ATTACHMENT_EXTENSION_BLOCKED", "出于安全原因，不允许上传该类型文件");
        }
        Set<String> allowed = stringList(field.get("allowedExtensions"));
        if (!allowed.contains(extension)) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "ATTACHMENT_EXTENSION_NOT_ALLOWED", "当前字段不允许上传 ." + extension + " 文件");
        }
        if (!settings.isRecognitionEnabled()) {
            return;
        }
        if ("mineru".equals(settings.getRecognitionEngine()) && !settings.getMineruSupportedExtensions().contains(extension)) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "ATTACHMENT_MINERU_EXTENSION_NOT_ALLOWED", "该扩展名未配置为复杂识别支持类型");
        }
        if ("local".equals(settings.getRecognitionEngine()) && !LOCAL_EXTENSIONS.contains(extension)) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "ATTACHMENT_LOCAL_EXTENSION_NOT_SUPPORTED", "简单识别暂不支持该类型文件");
        }
    }

    private InputAttachmentEntity requireAttachment(UUID tenantId, UUID runId, UUID nodeRunId, UUID attachmentId) {
        return attachmentRepository.findByIdAndTenantIdAndRunIdAndNodeRunId(attachmentId, tenantId, runId, nodeRunId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ATTACHMENT_NOT_FOUND", "附件不存在"));
    }

    private static void assertCanReadAll(CurrentUserPrincipal principal, Collection<InputAttachmentEntity> attachments) {
        for (InputAttachmentEntity attachment : attachments) {
            assertCanRead(principal, attachment);
        }
    }

    private static void assertCanRead(CurrentUserPrincipal principal, InputAttachmentEntity attachment) {
        if (!isTenantManager(principal) && !principal.userId().equals(attachment.getUploadedBy())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ATTACHMENT_READ_FORBIDDEN", "当前账号不能查看该附件");
        }
    }

    private static boolean isTenantManager(CurrentUserPrincipal principal) {
        return principal != null && ("tenant_admin".equals(principal.role()) || "system_admin".equals(principal.role()));
    }

    private static byte[] readBytes(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_FILE_REQUIRED", "请选择需要上传的附件");
        }
        try {
            return file.getBytes();
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_FILE_READ_FAILED", "附件读取失败，请重新选择文件");
        }
    }

    private static String normalizeFileName(String raw) {
        String leaf = raw == null ? "" : raw.replace('\\', '/');
        leaf = leaf.substring(leaf.lastIndexOf('/') + 1).trim();
        leaf = SAFE_FILE_NAME.matcher(leaf).replaceAll("-");
        if (leaf.isBlank() || leaf.length() > 255) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_FILE_NAME_INVALID", "附件文件名为空或长度超过 255 个字符");
        }
        return leaf;
    }

    private static String extensionOf(String fileName) {
        int index = fileName.lastIndexOf('.');
        if (index <= 0 || index == fileName.length() - 1) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "ATTACHMENT_EXTENSION_REQUIRED", "附件必须包含有效扩展名");
        }
        return fileName.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    private static String normalizeContentType(String extension) {
        // MIME 必须由服务端根据已校验的扩展名决定，不能信任浏览器上送值，否则恶意 HTML 可能借预览入口在同源 Blob 中执行。
        return switch (extension) {
            case "pdf" -> "application/pdf";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "tif", "tiff" -> "image/tiff";
            case "webp" -> "image/webp";
            case "txt", "md", "csv" -> "text/plain; charset=UTF-8";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "doc" -> "application/msword";
            case "xls" -> "application/vnd.ms-excel";
            default -> "application/octet-stream";
        };
    }

    private static void validateFileSignature(String extension, byte[] bytes) {
        boolean valid = switch (extension) {
            case "pdf" -> startsWith(bytes, "%PDF-".getBytes(StandardCharsets.US_ASCII));
            case "png" -> startsWith(bytes, new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47});
            case "jpg", "jpeg" -> startsWith(bytes, new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff});
            case "gif" -> startsWith(bytes, "GIF8".getBytes(StandardCharsets.US_ASCII));
            case "bmp" -> startsWith(bytes, new byte[] {0x42, 0x4d});
            case "tif", "tiff" -> startsWith(bytes, new byte[] {0x49, 0x49, 0x2a, 0x00})
                || startsWith(bytes, new byte[] {0x4d, 0x4d, 0x00, 0x2a});
            case "webp" -> bytes.length >= 12
                && startsWith(bytes, "RIFF".getBytes(StandardCharsets.US_ASCII))
                && new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP");
            case "docx", "xlsx" -> startsWith(bytes, new byte[] {0x50, 0x4b});
            case "doc", "xls" -> startsWith(bytes, new byte[] {(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0});
            case "txt", "md", "csv" -> !containsNullByte(bytes);
            default -> true;
        };
        if (!valid) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "ATTACHMENT_SIGNATURE_INVALID", "文件内容与扩展名不匹配，请检查文件后重新上传");
        }
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) {
            if (value[index] != prefix[index]) return false;
        }
        return true;
    }

    private static boolean containsNullByte(byte[] bytes) {
        int limit = Math.min(bytes.length, 8192);
        for (int index = 0; index < limit; index++) {
            if (bytes[index] == 0) return true;
        }
        return false;
    }

    private static Set<String> stringList(Object raw) {
        if (!(raw instanceof Collection<?> values)) {
            return Set.of();
        }
        return values.stream()
            .map(InputAttachmentService::stringValue)
            .map(value -> value.replaceFirst("^\\.", "").toLowerCase(Locale.ROOT))
            .filter(value -> !value.isBlank())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static List<UUID> parseAttachmentIds(Object raw) {
        if (!(raw instanceof Collection<?> values)) {
            return List.of();
        }
        try {
            return values.stream().map(value -> UUID.fromString(String.valueOf(value))).distinct().toList();
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_REFERENCE_INVALID", "附件标识格式不正确");
        }
    }

    private static int positiveInt(Object value, int fallback) {
        if (value instanceof Number number && number.intValue() > 0) {
            return number.intValue();
        }
        return fallback;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算附件摘要", exception);
        }
    }

    private static AttachmentApi.AttachmentRow toRow(InputAttachmentEntity attachment, AttachmentParseResultEntity result) {
        return new AttachmentApi.AttachmentRow(
            attachment.getId(), attachment.getFieldId(), attachment.getVariableKey(), attachment.getOriginalFileName(),
            attachment.getExtension(), attachment.getContentType(), attachment.getSizeBytes(), attachment.getRecognitionEngine(),
            attachment.getStatus(), attachment.getErrorCode(), attachment.getErrorMessage(), result == null ? null : result.getId(),
            result == null ? null : result.getCharacterCount(), result == null ? null : result.isTruncated(),
            attachment.getCreatedAt(), attachment.getUpdatedAt()
        );
    }

    private record RuntimeContext(WorkflowRunEntity run, WorkflowNodeRunEntity nodeRun) {
    }
}
