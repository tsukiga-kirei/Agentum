package com.agentum.system.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentum.system.application.SkillProbeRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilesystemSkillManifestProbeTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldProbeStandardSkillDirectory() throws Exception {
        Path skillDir = tempDir.resolve("capabilities/skills/demo-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
            ---
            name: demo-skill
            description: 演示 Skill 简介
            ---
            # Demo Skill
            """);
        Files.writeString(skillDir.resolve("skill.yaml"), """
            key: demo-skill
            name: 演示 Skill
            version: v1
            entry: SKILL.md
            description: 演示 Skill 简介
            inputs:
              - name: topic
                type: string
                required: true
                description: 业务主题
            """);

        FilesystemSkillManifestProbe probe = new FilesystemSkillManifestProbe(tempDir);
        var outcome = probe.probe(new SkillProbeRequest(
            UUID.randomUUID(),
            "capabilities/skills/demo-skill/SKILL.md",
            null
        ));

        assertThat(outcome.status()).isEqualTo("success");
        assertThat(outcome.summary()).contains("演示 Skill").contains("v1").contains("演示 Skill 简介");
        assertThat(outcome.tools()).hasSize(1);
        assertThat(outcome.tools().getFirst().name()).isEqualTo("演示 Skill");
        assertThat(outcome.tools().getFirst().description()).isEqualTo("演示 Skill 简介");
        assertThat(outcome.tools().getFirst().inputSchema()).containsKey("properties");
    }

    @Test
    void shouldFailWhenSkillMarkdownMissing() throws Exception {
        Path skillDir = tempDir.resolve("capabilities/skills/missing-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("skill.yaml"), "name: 缺失 Skill\nversion: v1\n");

        FilesystemSkillManifestProbe probe = new FilesystemSkillManifestProbe(tempDir);
        var outcome = probe.probe(new SkillProbeRequest(
            UUID.randomUUID(),
            "capabilities/skills/missing-skill/SKILL.md",
            null
        ));

        assertThat(outcome.status()).isEqualTo("failed");
        assertThat(outcome.summary()).contains("Skill 源码文件不存在");
    }
}
