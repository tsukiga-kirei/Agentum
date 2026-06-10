package com.agentum.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agentum.asset.domain.TenantAssetCapabilityEntity;
import com.agentum.asset.infrastructure.TenantAssetCapabilityRepository;
import com.agentum.system.infrastructure.SystemCapabilityRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PromptContentResolverTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final Instant NOW = Instant.parse("2026-06-05T09:30:00Z");

    @Test
    void shouldResolveInlinePromptsForAgentNodeSnapshot() {
        PromptContentResolver resolver = newResolver(mock(SystemCapabilityRepository.class), mock(TenantAssetCapabilityRepository.class));
        Map<String, Object> enriched = resolver.enrichConfigSnapshot(
            TENANT_ID,
            "agent",
            Map.of("systemPrompt", "系统正文", "userPrompt", "用户正文")
        );

        assertThat(enriched.get("resolvedSystemPrompt")).isEqualTo("系统正文");
        assertThat(enriched.get("resolvedUserPrompt")).isEqualTo("用户正文");
    }

    @Test
    void shouldResolveUserPromptTemplateAndEnrichClusterAgents() {
        TenantAssetCapabilityRepository assetRepository = mock(TenantAssetCapabilityRepository.class);
        TenantAssetCapabilityEntity template = TenantAssetCapabilityEntity.create(
            TENANT_ID,
            "prompt_template",
            "授信用户提示词",
            "credit_user_prompt",
            "v1",
            "授信分析用户提示词",
            "low",
            "published",
            "self",
            "self",
            null,
            Map.of("promptContent", "请分析 {{company}} 的授信风险。"),
            TENANT_ID,
            NOW
        );
        when(assetRepository.findByIdAndTenantId(template.getId(), TENANT_ID)).thenReturn(Optional.of(template));

        PromptContentResolver resolver = newResolver(mock(SystemCapabilityRepository.class), assetRepository);
        Map<String, Object> enriched = resolver.enrichConfigSnapshot(
            TENANT_ID,
            "parallel_group",
            Map.of(
                "clusterAgents",
                List.of(
                    Map.of(
                        "name", "子智能体 1",
                        "systemPrompt", "你是分析智能体",
                        "userPromptTemplateId", template.getId().toString()
                    )
                )
            )
        );

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> agents = (List<Map<String, Object>>) enriched.get("clusterAgents");
        assertThat(agents).hasSize(1);
        assertThat(agents.get(0).get("resolvedUserPrompt")).isEqualTo("请分析 {{company}} 的授信风险。");
        assertThat(agents.get(0).get("userPrompt")).isEqualTo("请分析 {{company}} 的授信风险。");
    }

    @Test
    void shouldReturnEmptyWhenPromptNotConfigured() {
        PromptContentResolver resolver = newResolver(mock(SystemCapabilityRepository.class), mock(TenantAssetCapabilityRepository.class));
        assertThat(resolver.resolveSystemPrompt(TENANT_ID, Map.of())).isEmpty();
        assertThat(resolver.resolveUserPrompt(TENANT_ID, Map.of())).isEmpty();
    }

    private PromptContentResolver newResolver(
        SystemCapabilityRepository systemCapabilityRepository,
        TenantAssetCapabilityRepository tenantAssetCapabilityRepository
    ) {
        return new PromptContentResolver(systemCapabilityRepository, tenantAssetCapabilityRepository);
    }
}
