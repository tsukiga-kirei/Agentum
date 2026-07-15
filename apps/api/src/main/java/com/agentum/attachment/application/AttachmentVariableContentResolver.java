package com.agentum.attachment.application;

import com.agentum.attachment.domain.AttachmentParseResultEntity;
import com.agentum.attachment.domain.InputAttachmentEntity;
import com.agentum.attachment.infrastructure.AttachmentParseResultRepository;
import com.agentum.attachment.infrastructure.InputAttachmentRepository;
import com.agentum.shared.api.RequestIds;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AttachmentVariableContentResolver {

    private static final Logger log = LoggerFactory.getLogger(AttachmentVariableContentResolver.class);
    private final InputAttachmentRepository attachmentRepository;
    private final AttachmentParseResultRepository parseResultRepository;
    private final AttachmentObjectStorage storage;

    public AttachmentVariableContentResolver(
        InputAttachmentRepository attachmentRepository,
        AttachmentParseResultRepository parseResultRepository,
        AttachmentObjectStorage storage
    ) {
        this.attachmentRepository = attachmentRepository;
        this.parseResultRepository = parseResultRepository;
        this.storage = storage;
    }

    /**
     * 变量快照只保存附件引用；真正调用模型时才按租户边界读取识别正文，避免在 JSONB 快照复制大段内容。
     */
    public String renderValue(UUID tenantId, Object value) {
        return renderValue(tenantId, value, true);
    }

    public String renderValue(UUID tenantId, Object value, boolean includeParsedContent) {
        List<UUID> attachmentIds = extractAttachmentIds(value);
        if (attachmentIds.isEmpty()) {
            return value == null ? "" : value.toString();
        }
        Map<UUID, InputAttachmentEntity> attachments = new LinkedHashMap<>();
        attachmentRepository.findByIdIn(attachmentIds).stream()
            .filter(item -> tenantId.equals(item.getTenantId()))
            .forEach(item -> attachments.put(item.getId(), item));
        Map<UUID, AttachmentParseResultEntity> results = new LinkedHashMap<>();
        parseResultRepository.findByAttachmentIdIn(attachmentIds)
            .forEach(item -> results.put(item.getAttachmentId(), item));

        StringBuilder rendered = new StringBuilder();
        for (UUID attachmentId : attachmentIds) {
            InputAttachmentEntity attachment = attachments.get(attachmentId);
            if (attachment == null) {
                continue;
            }
            rendered.append("<attachment id=\"").append(attachmentId).append("\" name=\"")
                .append(escapeAttribute(attachment.getOriginalFileName())).append("\" status=\"")
                .append(attachment.getStatus()).append("\">\n");
            AttachmentParseResultEntity result = results.get(attachmentId);
            if (!includeParsedContent) {
                rendered.append("附件正文不会注入系统提示词，请在用户提示词中引用该变量。");
            } else if (result != null && "ready".equals(result.getStatus()) && result.getContentStorageKey() != null) {
                rendered.append(new String(storage.read(result.getContentStorageKey()), StandardCharsets.UTF_8));
            } else {
                rendered.append("附件未生成可供模型读取的识别正文。");
            }
            rendered.append("\n</attachment>\n");
        }
        log.debug(
            "附件变量正文已装载 tenantId={} attachmentCount={} requestId={}",
            tenantId, attachments.size(), RequestIds.current()
        );
        return rendered.toString().trim();
    }

    private static List<UUID> extractAttachmentIds(Object value) {
        if (!(value instanceof Collection<?> values)) {
            return List.of();
        }
        List<UUID> result = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof Map<?, ?> map) || map.get("attachmentId") == null) {
                return List.of();
            }
            try {
                result.add(UUID.fromString(String.valueOf(map.get("attachmentId"))));
            } catch (IllegalArgumentException exception) {
                return List.of();
            }
        }
        return result.stream().distinct().toList();
    }

    private static String escapeAttribute(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
