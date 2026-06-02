package com.agentum.system.application;

/**
 * Skill 源文件探测入口。系统管理页通过读取标准目录中的 SKILL.md 与 skill.yaml 验证 Skill 是否可加载。
 */
public interface SkillManifestProbe {

    SkillProbeOutcome probe(SkillProbeRequest request);
}
