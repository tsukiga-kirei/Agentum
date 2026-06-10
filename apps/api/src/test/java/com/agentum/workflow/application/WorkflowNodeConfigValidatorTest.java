package com.agentum.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.agentum.asset.application.AssetManagementService;
import com.agentum.system.domain.SystemCapabilityEntity;
import com.agentum.system.domain.TenantCapabilityGrantEntity;
import com.agentum.system.infrastructure.SystemCapabilityRepository;
import com.agentum.system.infrastructure.TenantCapabilityGrantRepository;
import com.agentum.workflow.interfaces.WorkflowDraftApi;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkflowNodeConfigValidatorTest {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final Instant NOW = Instant.parse("2026-06-04T08:00:00Z");

    @Mock
    private SystemCapabilityRepository systemCapabilityRepository;
    @Mock
    private TenantCapabilityGrantRepository tenantCapabilityGrantRepository;
    @Mock
    private AssetManagementService assetManagementService;

    @Test
    void shouldRejectAgentNodeWhenCustomPromptsAreBlank() {
        WorkflowDraftApi.WorkflowNodeRow node = new WorkflowDraftApi.WorkflowNodeRow(
            "agent_1",
            "agent",
            "合同分析",
            0,
            0,
            List.of(),
            List.of(),
            Map.of(
                "systemPromptTemplateId", "none",
                "userPromptTemplateId", "none",
                "systemPrompt", "",
                "userPrompt", ""
            )
        );

        List<WorkflowDraftApi.WorkflowValidationIssue> issues = validator().validateCapabilityReferences(TENANT_ID, USER_ID, List.of(node));

        assertThat(issues).extracting(WorkflowDraftApi.WorkflowValidationIssue::code)
            .containsExactlyInAnyOrder(
                "WORKFLOW_VALIDATION_SYSTEM_PROMPT_REQUIRED",
                "WORKFLOW_VALIDATION_USER_PROMPT_REQUIRED"
            );
    }

    @Test
    void shouldAcceptAgentNodeWhenCustomPromptsAreProvided() {
        WorkflowDraftApi.WorkflowNodeRow node = new WorkflowDraftApi.WorkflowNodeRow(
            "agent_1",
            "agent",
            "合同分析",
            0,
            0,
            List.of(),
            List.of(),
            Map.of(
                "systemPromptTemplateId", "none",
                "userPromptTemplateId", "none",
                "systemPrompt", WorkflowPromptDefaults.DEFAULT_SYSTEM_PROMPT,
                "userPrompt", WorkflowPromptDefaults.DEFAULT_USER_PROMPT
            )
        );

        List<WorkflowDraftApi.WorkflowValidationIssue> issues = validator().validateCapabilityReferences(TENANT_ID, USER_ID, List.of(node));

        assertThat(issues).isEmpty();
    }

    @Test
    void shouldRejectSystemCapabilityNotAssignedToCurrentEditor() {
        SystemCapabilityEntity skill = SystemCapabilityEntity.create(
            "skill", "合同解析", "contract_parse", "v1", "", "low", "active", Map.of(), NOW
        );
        TenantCapabilityGrantEntity tenantGrant = TenantCapabilityGrantEntity.create(TENANT_ID, skill.getId(), "enabled", NOW);
        when(tenantCapabilityGrantRepository.findByTenantIdOrderByCreatedAtDesc(TENANT_ID)).thenReturn(List.of(tenantGrant));
        when(systemCapabilityRepository.findAllById(any())).thenReturn(List.of(skill));
        when(assetManagementService.canUseSystemCapabilityReference(TENANT_ID, USER_ID, skill.getId(), "skill")).thenReturn(false);

        WorkflowDraftApi.WorkflowNodeRow node = new WorkflowDraftApi.WorkflowNodeRow(
            "agent_1", "agent", "合同分析", 0, 0, List.of(), List.of(), Map.of("skillIds", List.of(skill.getId().toString()))
        );

        List<WorkflowDraftApi.WorkflowValidationIssue> issues = validator().validateCapabilityReferences(TENANT_ID, USER_ID, List.of(node));

        assertThat(issues).extracting(WorkflowDraftApi.WorkflowValidationIssue::code)
            .containsExactly("WORKFLOW_VALIDATION_CAPABILITY_NOT_ASSIGNED");
    }

    private WorkflowNodeConfigValidator validator() {
        return new WorkflowNodeConfigValidator(systemCapabilityRepository, tenantCapabilityGrantRepository, assetManagementService);
    }
}
