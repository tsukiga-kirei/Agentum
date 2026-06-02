-- Skill 配置收敛为单一 SKILL.md 路径；skill.yaml 固定与 SKILL.md 同目录，不再单独登记 manifestPath。
UPDATE system_capabilities
SET config = jsonb_build_object(
    'sourcePath', 'capabilities/skills/agentum-connectivity-check/SKILL.md'
)
WHERE capability_type = 'skill'
  AND code = 'requirement_breakdown';

UPDATE system_capabilities
SET config = config - 'manifestPath'
WHERE capability_type = 'skill'
  AND config ? 'manifestPath';
