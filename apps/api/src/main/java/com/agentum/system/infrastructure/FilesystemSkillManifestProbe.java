package com.agentum.system.infrastructure;

import com.agentum.system.application.SkillManifestProbe;
import com.agentum.system.application.SkillProbeOutcome;
import com.agentum.system.application.SkillProbeRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

@Component
public class FilesystemSkillManifestProbe implements SkillManifestProbe {

    private static final Logger log = LoggerFactory.getLogger(FilesystemSkillManifestProbe.class);
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile("^---\\s*\\R(.*?)\\R---", Pattern.DOTALL);

    private final Path repoRoot;
    private final Yaml yaml = new Yaml();

    public FilesystemSkillManifestProbe() {
        this(locateRepoRoot());
    }

    FilesystemSkillManifestProbe(Path repoRoot) {
        this.repoRoot = repoRoot;
    }

    @Override
    public SkillProbeOutcome probe(SkillProbeRequest request) {
        try {
            ResolvedSkillPaths paths = resolvePaths(request.sourcePath(), request.legacyManifestPath());
            if (!Files.isRegularFile(paths.skillMarkdown())) {
                return failed("Skill 源码文件不存在：" + repoRoot.relativize(paths.skillMarkdown()));
            }
            if (!Files.isRegularFile(paths.manifest())) {
                return failed("Skill 清单文件不存在：" + repoRoot.relativize(paths.manifest()));
            }

            Map<String, Object> manifest = readYamlMap(paths.manifest());
            Map<String, String> frontmatter = readFrontmatter(paths.skillMarkdown());
            SkillMetadata metadata = mergeMetadata(manifest, frontmatter);

            if (metadata.name() == null || metadata.name().isBlank()) {
                return failed("Skill 清单缺少 name 字段，请检查 skill.yaml 或 SKILL.md frontmatter");
            }

            Map<String, Object> inputSchema = buildInputSchema(manifest.get("inputs"));
            String summary = buildSummary(metadata);
            SkillProbeOutcome.SkillPreview preview = new SkillProbeOutcome.SkillPreview(
                metadata.name(),
                metadata.description() == null ? "" : metadata.description(),
                inputSchema
            );
            return new SkillProbeOutcome("success", summary, List.of(preview));
        } catch (IllegalArgumentException ex) {
            return failed(ex.getMessage());
        } catch (Exception ex) {
            log.warn(
                "系统管理 Skill 源文件探测失败 capabilityId={} sourcePath={} errorType={}",
                request.capabilityId(),
                request.sourcePath(),
                ex.getClass().getSimpleName()
            );
            return failed("Skill 源文件读取失败：" + ex.getMessage());
        }
    }

    static Path locateRepoRoot() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 6; depth++) {
            if (Files.isDirectory(current.resolve("capabilities/skills"))) {
                return current;
            }
            Path parent = current.getParent();
            if (parent == null) {
                break;
            }
            current = parent;
        }
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    private ResolvedSkillPaths resolvePaths(String sourcePath, String legacyManifestPath) {
        if (sourcePath != null && !sourcePath.isBlank()) {
            Path skillMarkdown = resolveRepoRelativePath(sourcePath.trim());
            Path manifest = skillMarkdown.getParent().resolve("skill.yaml").normalize();
            return new ResolvedSkillPaths(skillMarkdown, manifest);
        }
        if (legacyManifestPath != null && !legacyManifestPath.isBlank()) {
            Path manifest = resolveRepoRelativePath(legacyManifestPath.trim());
            Path skillMarkdown = manifest.getParent().resolve("SKILL.md").normalize();
            return new ResolvedSkillPaths(skillMarkdown, manifest);
        }
        throw new IllegalArgumentException("请配置 Skill 源码路径（SKILL.md）");
    }

    private Path resolveRepoRelativePath(String relativePath) {
        Path normalized = repoRoot.resolve(relativePath).normalize();
        if (!normalized.startsWith(repoRoot)) {
            throw new IllegalArgumentException("Skill 路径超出仓库根目录范围");
        }
        return normalized;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readYamlMap(Path manifestPath) throws IOException {
        String content = Files.readString(manifestPath, StandardCharsets.UTF_8);
        Object parsed = yaml.load(content);
        if (!(parsed instanceof Map<?, ?> rawMap)) {
            throw new IllegalArgumentException("skill.yaml 格式无效，根节点必须是对象");
        }
        return (Map<String, Object>) rawMap;
    }

    private Map<String, String> readFrontmatter(Path skillMarkdownPath) throws IOException {
        String content = Files.readString(skillMarkdownPath, StandardCharsets.UTF_8);
        Matcher matcher = FRONTMATTER_PATTERN.matcher(content);
        if (!matcher.find()) {
            return Map.of();
        }
        Object parsed = yaml.load(matcher.group(1));
        if (!(parsed instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        Map<String, String> frontmatter = new LinkedHashMap<>();
        rawMap.forEach((key, value) -> {
            if (key != null && value != null) {
                frontmatter.put(String.valueOf(key), String.valueOf(value).trim());
            }
        });
        return frontmatter;
    }

    private SkillMetadata mergeMetadata(Map<String, Object> manifest, Map<String, String> frontmatter) {
        String name = firstNonBlank(stringValue(manifest.get("name")), frontmatter.get("name"));
        String description = firstNonBlank(stringValue(manifest.get("description")), frontmatter.get("description"));
        String version = firstNonBlank(stringValue(manifest.get("version")), frontmatter.get("version"), "v1");
        String key = firstNonBlank(stringValue(manifest.get("key")), frontmatter.get("name"));
        return new SkillMetadata(key, name, description, version);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildInputSchema(Object inputsValue) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        if (inputsValue instanceof List<?> inputs) {
            for (Object item : inputs) {
                if (!(item instanceof Map<?, ?> rawInput)) {
                    continue;
                }
                String name = stringValue(rawInput.get("name"));
                if (name == null) {
                    continue;
                }
                Map<String, Object> property = new LinkedHashMap<>();
                property.put("type", firstNonBlank(stringValue(rawInput.get("type")), "string"));
                String description = stringValue(rawInput.get("description"));
                if (description != null) {
                    property.put("description", description);
                }
                properties.put(name, property);
                if (Boolean.TRUE.equals(rawInput.get("required"))) {
                    required.add(name);
                }
            }
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    private static String buildSummary(SkillMetadata metadata) {
        StringBuilder summary = new StringBuilder("Skill 连接成功：")
            .append(metadata.name())
            .append("（")
            .append(metadata.version())
            .append("）");
        if (metadata.description() != null && !metadata.description().isBlank()) {
            summary.append(" — ").append(metadata.description());
        }
        return summary.toString();
    }

    private static SkillProbeOutcome failed(String summary) {
        return new SkillProbeOutcome("failed", summary, List.of());
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private record ResolvedSkillPaths(Path skillMarkdown, Path manifest) {
    }

    private record SkillMetadata(String key, String name, String description, String version) {
    }
}
