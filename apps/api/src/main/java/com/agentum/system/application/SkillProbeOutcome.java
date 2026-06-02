package com.agentum.system.application;

import java.util.List;
import java.util.Map;

/**
 * Skill 连通性探测结果。tools 复用能力测试弹窗结构，展示 Skill 名称、简介和输入 Schema。
 */
public record SkillProbeOutcome(
    String status,
    String summary,
    List<SkillPreview> tools
) {

    public record SkillPreview(String name, String description, Map<String, Object> inputSchema) {
    }
}
