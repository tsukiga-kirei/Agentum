package com.agentum.system.application;

import java.util.UUID;

public record SkillProbeRequest(UUID capabilityId, String sourcePath, String legacyManifestPath) {
}
