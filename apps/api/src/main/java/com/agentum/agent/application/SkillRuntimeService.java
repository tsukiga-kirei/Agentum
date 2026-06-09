package com.agentum.agent.application;

import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.RequestIds;
import com.agentum.system.domain.SystemCapabilityEntity;
import com.agentum.system.infrastructure.SystemCapabilityRepository;
import com.agentum.system.infrastructure.TenantCapabilityGrantRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 产品运行时 Skill 服务。
 *
 * <p>Skill 在 Agentum 中不是后端硬编码函数，而是智能体可按需阅读的能力说明书。
 * 这里将已分配给当前节点的 Skill 转换为模型工具：模型先读说明，再基于说明决定如何完成业务任务。
 * 文件读取被限制在 SKILL.md 同目录下，避免模型通过 filePath 参数越权读取仓库其它文件。</p>
 */
@Service
public class SkillRuntimeService {

    private static final Logger log = LoggerFactory.getLogger(SkillRuntimeService.class);
    private static final Set<String> SENTINEL_VALUES = Set.of("", "none", "custom");
    private static final int MAX_SKILL_CONTENT_CHARS = 12000;

    private final SystemCapabilityRepository systemCapabilityRepository;
    private final TenantCapabilityGrantRepository tenantCapabilityGrantRepository;

    public SkillRuntimeService(
        SystemCapabilityRepository systemCapabilityRepository,
        TenantCapabilityGrantRepository tenantCapabilityGrantRepository
    ) {
        this.systemCapabilityRepository = systemCapabilityRepository;
        this.tenantCapabilityGrantRepository = tenantCapabilityGrantRepository;
    }

    public List<SkillToolBinding> resolveSkillTools(UUID tenantId, Map<String, Object> nodeConfig) {
        List<String> skillIds = readStringList(nodeConfig, "skillIds", "skills", "skillId");
        if (skillIds.isEmpty()) {
            return List.of();
        }

        List<SkillToolBinding> bindings = new ArrayList<>();
        for (String skillId : skillIds) {
            UUID capabilityId = parseUuid(skillId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "SKILL_CAPABILITY_ID_INVALID", "Skill 能力 ID 不合法"));
            SystemCapabilityEntity capability = resolveSkillCapability(tenantId, capabilityId);
            String sourcePath = stringValue(capability.getConfig().get("sourcePath"));
            if (sourcePath.isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "SKILL_SOURCE_PATH_REQUIRED", "Skill 能力未配置 SKILL.md 路径");
            }
            bindings.add(new SkillToolBinding(
                sanitizeToolName("skill_" + capability.getCode() + "_read"),
                capability.getId(),
                capability.getCode(),
                capability.getName(),
                capability.getDescription(),
                sourcePath
            ));
        }
        return bindings;
    }

    public SkillReadResult readSkill(SkillToolBinding binding, Map<String, Object> rawArguments) {
        Map<String, Object> arguments = rawArguments == null ? Map.of() : rawArguments;
        String relativeFilePath = firstNonBlank(
            stringValue(arguments.get("filePath")),
            stringValue(arguments.get("file_path")),
            "SKILL.md"
        );
        if (relativeFilePath.startsWith("/") || relativeFilePath.contains("..")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SKILL_FILE_PATH_INVALID", "Skill 文件路径只能是 SKILL.md 同目录下的相对路径");
        }

        Path skillPath = normalizeSourcePath(binding.sourcePath());
        Path baseDir = skillPath.getParent() == null ? Path.of("").toAbsolutePath() : skillPath.getParent();
        Path target = baseDir.resolve(relativeFilePath).normalize();
        if (!target.startsWith(baseDir.normalize())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SKILL_FILE_PATH_INVALID", "Skill 文件路径不能越过能力目录");
        }
        if (!Files.exists(target) || !Files.isRegularFile(target)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SKILL_FILE_NOT_FOUND", "Skill 文件不存在");
        }

        try {
            String content = Files.readString(target, StandardCharsets.UTF_8);
            boolean truncated = content.length() > MAX_SKILL_CONTENT_CHARS;
            String visibleContent = truncated ? content.substring(0, MAX_SKILL_CONTENT_CHARS) + "\n\n[内容已截断]" : content;
            return new SkillReadResult(binding.skillCode(), binding.displayName(), relativeFilePath, visibleContent, truncated);
        } catch (IOException exception) {
            log.warn(
                "读取 Skill 文件失败 skillId={} skillCode={} path={} requestId={}",
                binding.skillId(),
                binding.skillCode(),
                target,
                RequestIds.current(),
                exception
            );
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SKILL_FILE_READ_FAILED", "Skill 文件读取失败");
        }
    }

    private SystemCapabilityEntity resolveSkillCapability(UUID tenantId, UUID capabilityId) {
        SystemCapabilityEntity capability = systemCapabilityRepository.findById(capabilityId)
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "SKILL_CAPABILITY_NOT_FOUND", "Skill 能力不存在"));
        if (!"active".equals(capability.getStatus()) || !"skill".equals(capability.getCapabilityType())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SKILL_CAPABILITY_NOT_ACTIVE", "Skill 能力未启用或类型不匹配");
        }
        boolean granted = tenantCapabilityGrantRepository.findByTenantIdAndCapabilityId(tenantId, capabilityId)
            .filter(grant -> "enabled".equals(grant.getStatus()))
            .isPresent();
        if (!granted) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SKILL_CAPABILITY_NOT_ASSIGNED", "该 Skill 能力未分配给当前租户");
        }
        return capability;
    }

    private static Path normalizeSourcePath(String sourcePath) {
        Path rawPath = Path.of(sourcePath);
        return rawPath.isAbsolute() ? rawPath.normalize() : Path.of("").toAbsolutePath().resolve(rawPath).normalize();
    }

    private static List<String> readStringList(Map<String, Object> config, String... keys) {
        Map<String, Object> safeConfig = config == null ? Map.of() : config;
        List<String> result = new ArrayList<>();
        for (String key : keys) {
            Object value = safeConfig.get(key);
            if (value instanceof List<?> list) {
                list.stream()
                    .map(item -> item == null ? "" : item.toString().trim())
                    .filter(text -> !SENTINEL_VALUES.contains(text))
                    .forEach(result::add);
            } else {
                String text = value == null ? "" : value.toString().trim();
                if (!SENTINEL_VALUES.contains(text)) {
                    result.add(text);
                }
            }
            if (!result.isEmpty()) {
                return result;
            }
        }
        return result;
    }

    private static Optional<UUID> parseUuid(String value) {
        try {
            return value == null || value.isBlank() ? Optional.empty() : Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public static Map<String, Object> skillToolParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of(
            "type", "string",
            "description", "可选，读取 SKILL.md 同目录下的补充文件；默认读取 SKILL.md"
        ));
        return Map.of(
            "type", "object",
            "properties", properties
        );
    }

    private static String sanitizeToolName(String value) {
        String sanitized = value == null ? "" : value.replaceAll("[^A-Za-z0-9_\\-]", "_");
        return sanitized.isBlank() ? "skill_read" : sanitized;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    public record SkillToolBinding(
        String functionName,
        UUID skillId,
        String skillCode,
        String displayName,
        String description,
        String sourcePath
    ) {
    }

    public record SkillReadResult(
        String skillCode,
        String skillName,
        String filePath,
        String content,
        boolean truncated
    ) {
        public Map<String, Object> toMap() {
            return Map.of(
                "skillCode", skillCode,
                "skillName", skillName,
                "filePath", filePath,
                "content", content,
                "truncated", truncated
            );
        }
    }
}
