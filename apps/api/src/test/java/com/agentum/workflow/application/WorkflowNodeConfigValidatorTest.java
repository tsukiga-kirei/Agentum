package com.agentum.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.agentum.asset.application.AssetManagementService;
import com.agentum.agent.application.AgentRuntimeProperties;
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
                "userPrompt", "",
                "maxAgentIterationsPerTurn", 4
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
                "userPrompt", WorkflowPromptDefaults.DEFAULT_USER_PROMPT,
                "maxAgentIterationsPerTurn", 4
            )
        );

        List<WorkflowDraftApi.WorkflowValidationIssue> issues = validator().validateCapabilityReferences(TENANT_ID, USER_ID, List.of(node));

        assertThat(issues).isEmpty();
    }

    @Test
    void shouldRejectAgentNodeWhenIterationLimitIsMissing() {
        WorkflowDraftApi.WorkflowNodeRow node = new WorkflowDraftApi.WorkflowNodeRow(
            "agent_1",
            "agent",
            "合同分析",
            0,
            0,
            List.of(),
            List.of(),
            Map.of(
                "systemPrompt", WorkflowPromptDefaults.DEFAULT_SYSTEM_PROMPT,
                "userPrompt", WorkflowPromptDefaults.DEFAULT_USER_PROMPT
            )
        );

        List<WorkflowDraftApi.WorkflowValidationIssue> issues = validator().validateCapabilityReferences(TENANT_ID, USER_ID, List.of(node));

        assertThat(issues).extracting(WorkflowDraftApi.WorkflowValidationIssue::code)
            .containsExactly("WORKFLOW_VALIDATION_AGENT_ITERATIONS_REQUIRED");
    }

    @Test
    void shouldRejectAgentNodeWhenIterationLimitExceedsPlatformMaximum() {
        WorkflowDraftApi.WorkflowNodeRow node = new WorkflowDraftApi.WorkflowNodeRow(
            "agent_1",
            "agent",
            "合同分析",
            0,
            0,
            List.of(),
            List.of(),
            Map.of(
                "systemPrompt", WorkflowPromptDefaults.DEFAULT_SYSTEM_PROMPT,
                "userPrompt", WorkflowPromptDefaults.DEFAULT_USER_PROMPT,
                "maxAgentIterationsPerTurn", 21
            )
        );

        List<WorkflowDraftApi.WorkflowValidationIssue> issues = validator().validateCapabilityReferences(TENANT_ID, USER_ID, List.of(node));

        assertThat(issues).extracting(WorkflowDraftApi.WorkflowValidationIssue::code)
            .containsExactly("WORKFLOW_VALIDATION_AGENT_ITERATIONS_INVALID");
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
                "userPrompt", WorkflowPromptDefaults.DEFAULT_USER_PROMPT,
                "maxAgentIterationsPerTurn", 4,
                "skillIds", List.of(skill.getId().toString())
            )
        );

        List<WorkflowDraftApi.WorkflowValidationIssue> issues = validator().validateCapabilityReferences(TENANT_ID, USER_ID, List.of(node));

        assertThat(issues).extracting(WorkflowDraftApi.WorkflowValidationIssue::code)
            .containsExactly("WORKFLOW_VALIDATION_CAPABILITY_NOT_ASSIGNED");
    }

    @Test
    void shouldRejectSelectInputFieldWithoutOptions() {
        WorkflowDraftApi.WorkflowNodeRow node = new WorkflowDraftApi.WorkflowNodeRow(
            "input_1",
            "user_input",
            "资料输入",
            0,
            0,
            List.of("starter"),
            List.of("company_type"),
            Map.of(
                "inputFields", List.of(Map.of(
                    "id", "field_1",
                    "label", "企业类型",
                    "variable", "company_type",
                    "fieldType", "select",
                    "placeholder", "请选择企业类型",
                    "options", List.of()
                ))
            )
        );

        List<WorkflowDraftApi.WorkflowValidationIssue> issues = validator().validateCapabilityReferences(TENANT_ID, USER_ID, List.of(node));

        assertThat(issues).extracting(WorkflowDraftApi.WorkflowValidationIssue::code)
            .containsExactly("WORKFLOW_VALIDATION_INPUT_FIELD_OPTIONS_REQUIRED");
    }

    @Test
    void shouldRejectTextFieldUsingDateDefaultRule() {
        WorkflowDraftApi.WorkflowNodeRow node = new WorkflowDraftApi.WorkflowNodeRow(
            "input_1",
            "user_input",
            "资料输入",
            0,
            0,
            List.of("starter"),
            List.of("report_date"),
            Map.of("inputFields", List.of(Map.of(
                "id", "field_1",
                "label", "报告日期",
                "variable", "report_date",
                "fieldType", "text",
                "defaultValueSource", "system",
                "systemDefaultValue", "current_date"
            )))
        );

        List<WorkflowDraftApi.WorkflowValidationIssue> issues = validator().validateCapabilityReferences(TENANT_ID, USER_ID, List.of(node));

        assertThat(issues).extracting(WorkflowDraftApi.WorkflowValidationIssue::code)
            .containsExactly("WORKFLOW_VALIDATION_INPUT_SYSTEM_DEFAULT_INVALID");
    }

    @Test
    void shouldRejectSelectInputFieldWhenOnlyPlaceholderOptionExists() {
        WorkflowDraftApi.WorkflowNodeRow node = new WorkflowDraftApi.WorkflowNodeRow(
            "input_1",
            "user_input",
            "资料输入",
            0,
            0,
            List.of("starter"),
            List.of("company_type"),
            Map.of(
                "inputFields", List.of(Map.of(
                    "id", "field_1",
                    "label", "企业类型",
                    "variable", "company_type",
                    "fieldType", "select",
                    "placeholder", "请选择企业类型",
                    "options", List.of(Map.of(
                        "label", "请选择企业类型",
                        "value", "请选择企业类型"
                    ))
                ))
            )
        );

        List<WorkflowDraftApi.WorkflowValidationIssue> issues = validator().validateCapabilityReferences(TENANT_ID, USER_ID, List.of(node));

        assertThat(issues).extracting(WorkflowDraftApi.WorkflowValidationIssue::code)
            .containsExactly("WORKFLOW_VALIDATION_INPUT_FIELD_OPTIONS_REQUIRED");
    }

    @Test
    void shouldRejectInputNodeWhenInputFieldsDoNotMatchOutputs() {
        WorkflowDraftApi.WorkflowNodeRow node = new WorkflowDraftApi.WorkflowNodeRow(
            "input_1",
            "user_input",
            "资料输入",
            0,
            0,
            List.of("starter"),
            List.of("company_name"),
            Map.of(
                "inputFields", List.of(Map.of(
                    "id", "field_1",
                    "label", "企业名称",
                    "variable", "input_1",
                    "placeholder", "请输入企业名称"
                ))
            )
        );

        List<WorkflowDraftApi.WorkflowValidationIssue> issues = validator().validateCapabilityReferences(TENANT_ID, USER_ID, List.of(node));

        assertThat(issues).extracting(WorkflowDraftApi.WorkflowValidationIssue::code)
            .containsExactly("WORKFLOW_VALIDATION_INPUT_OUTPUT_MISMATCH");
    }

    @Test
    void shouldRejectClusterNodeWithoutAgents() {
        WorkflowDraftApi.WorkflowNodeRow node = new WorkflowDraftApi.WorkflowNodeRow(
            "cluster_1",
            "parallel_group",
            "智能体集群",
            0,
            0,
            List.of("company_name"),
            List.of(),
            Map.of("clusterAgents", List.of())
        );

        List<WorkflowDraftApi.WorkflowValidationIssue> issues = validator().validateCapabilityReferences(TENANT_ID, USER_ID, List.of(node));

        assertThat(issues).extracting(WorkflowDraftApi.WorkflowValidationIssue::code)
            .containsExactly("WORKFLOW_VALIDATION_CLUSTER_AGENTS_REQUIRED");
    }

    @Test
    void shouldRejectClusterNodeWhenAgentOutputsDoNotMatchNodeOutputs() {
        WorkflowDraftApi.WorkflowNodeRow node = new WorkflowDraftApi.WorkflowNodeRow(
            "cluster_1",
            "parallel_group",
            "智能体集群",
            0,
            0,
            List.of("company_name"),
            List.of("cluster_result"),
            Map.of(
                "executionMode", "collaborative",
                "clusterAgents", List.of(
                    Map.of(
                        "name", "资料核验",
                        "systemPromptTemplateId", "none",
                        "userPromptTemplateId", "none",
                        "systemPrompt", WorkflowPromptDefaults.DEFAULT_SYSTEM_PROMPT,
                        "userPrompt", WorkflowPromptDefaults.DEFAULT_CLUSTER_USER_PROMPT,
                        "maxAgentIterationsPerTurn", 4,
                        "output", "agent_1_output"
                    )
                )
            )
        );

        List<WorkflowDraftApi.WorkflowValidationIssue> issues = validator().validateCapabilityReferences(TENANT_ID, USER_ID, List.of(node));

        assertThat(issues).extracting(WorkflowDraftApi.WorkflowValidationIssue::code)
            .containsExactly("WORKFLOW_VALIDATION_CLUSTER_OUTPUT_MISMATCH");
    }

    @Test
    void shouldAcceptClusterNodeWhenAgentOutputsMatchNodeOutputs() {
        WorkflowDraftApi.WorkflowNodeRow node = new WorkflowDraftApi.WorkflowNodeRow(
            "cluster_1",
            "parallel_group",
            "智能体集群",
            0,
            0,
            List.of("company_name"),
            List.of("cluster_result", "agent_1_output"),
            Map.of(
                "executionMode", "collaborative",
                "clusterOutputVariable", "cluster_result",
                "mergeRule", "{{agent_1_output}}",
                "clusterAgents", List.of(
                    Map.of(
                        "name", "资料核验",
                        "systemPromptTemplateId", "none",
                        "userPromptTemplateId", "none",
                        "systemPrompt", WorkflowPromptDefaults.DEFAULT_SYSTEM_PROMPT,
                        "userPrompt", WorkflowPromptDefaults.DEFAULT_CLUSTER_USER_PROMPT,
                        "maxAgentIterationsPerTurn", 4,
                        "output", "agent_1_output"
                    )
                )
            )
        );

        List<WorkflowDraftApi.WorkflowValidationIssue> issues = validator().validateCapabilityReferences(TENANT_ID, USER_ID, List.of(node));

        assertThat(issues).isEmpty();
    }

    @Test
    void shouldRejectLegacyClusterExecutionModeAfterMigration() {
        WorkflowDraftApi.WorkflowNodeRow node = new WorkflowDraftApi.WorkflowNodeRow(
            "cluster_1",
            "parallel_group",
            "智能体集群",
            0,
            0,
            List.of("company_name"),
            List.of("cluster_result", "agent_1_output"),
            Map.of(
                "executionMode", "parallel",
                "clusterOutputVariable", "cluster_result",
                "mergeRule", "{{agent_1_output}}",
                "clusterAgents", List.of(
                    Map.of(
                        "name", "资料核验",
                        "systemPromptTemplateId", "none",
                        "userPromptTemplateId", "none",
                        "systemPrompt", WorkflowPromptDefaults.DEFAULT_SYSTEM_PROMPT,
                        "userPrompt", WorkflowPromptDefaults.DEFAULT_CLUSTER_USER_PROMPT,
                        "maxAgentIterationsPerTurn", 4,
                        "output", "agent_1_output"
                    )
                )
            )
        );

        List<WorkflowDraftApi.WorkflowValidationIssue> issues = validator().validateCapabilityReferences(TENANT_ID, USER_ID, List.of(node));

        assertThat(issues).extracting(WorkflowDraftApi.WorkflowValidationIssue::code)
            .containsExactly("WORKFLOW_VALIDATION_EXECUTION_MODE_INVALID");
    }

    @Test
    void shouldAcceptIntentClusterWhenRoutesAndOutputAreConfigured() {
        WorkflowDraftApi.WorkflowNodeRow node = new WorkflowDraftApi.WorkflowNodeRow(
            "cluster_1",
            "parallel_group",
            "智能体集群",
            0,
            0,
            List.of("company_name"),
            List.of("cluster_result"),
            Map.of(
                "executionMode", "intent",
                "intentSelectionMode", "multiple",
                "intentInputTemplate", "请根据 {{company_name}} 判断要执行哪些报告智能体。",
                "intentFallbackMode", "agent",
                "fallbackAgentId", "other",
                "intentRoutes", List.of(
                    Map.of("intentCode", "monthly_report", "intentName", "月报", "intentDescription", "处理月报生成", "agentId", "monthly_report")
                ),
                "clusterAgents", List.of(
                    intentAgent("月报智能体", "monthly_report", "处理月报生成", "monthly_output"),
                    intentAgent("其他处理智能体", "other", "处理无法归类的需求", "other_output")
                )
            )
        );

        List<WorkflowDraftApi.WorkflowValidationIssue> issues = validator().validateCapabilityReferences(TENANT_ID, USER_ID, List.of(node));

        assertThat(issues).isEmpty();
    }

    @Test
    void shouldRejectIntentClusterWhenFallbackAgentIsMissing() {
        WorkflowDraftApi.WorkflowNodeRow node = new WorkflowDraftApi.WorkflowNodeRow(
            "cluster_1",
            "parallel_group",
            "智能体集群",
            0,
            0,
            List.of("company_name"),
            List.of("monthly_output"),
            Map.of(
                "executionMode", "intent",
                "intentSelectionMode", "multiple",
                "intentInputTemplate", "请根据 {{company_name}} 判断要执行哪些报告智能体。",
                "intentFallbackMode", "agent",
                "fallbackAgentId", "other",
                "intentRoutes", List.of(
                    Map.of("intentCode", "monthly_report", "intentName", "月报", "intentDescription", "处理月报生成", "agentId", "monthly_report")
                ),
                "clusterAgents", List.of(
                    intentAgent("月报智能体", "monthly_report", "处理月报生成", "monthly_output")
                )
            )
        );

        List<WorkflowDraftApi.WorkflowValidationIssue> issues = validator().validateCapabilityReferences(TENANT_ID, USER_ID, List.of(node));

        assertThat(issues).extracting(WorkflowDraftApi.WorkflowValidationIssue::code)
            .contains(
                "WORKFLOW_VALIDATION_CLUSTER_INTENT_OUTPUT_INVALID",
                "WORKFLOW_VALIDATION_CLUSTER_INTENT_FALLBACK_AGENT_MISSING"
            );
    }

    @Test
    void shouldRejectIntentClusterWhenInputTemplateIsMissing() {
        WorkflowDraftApi.WorkflowNodeRow node = new WorkflowDraftApi.WorkflowNodeRow(
            "cluster_1",
            "parallel_group",
            "智能体集群",
            0,
            0,
            List.of("company_name"),
            List.of("cluster_result"),
            Map.of(
                "executionMode", "intent",
                "intentSelectionMode", "multiple",
                "intentFallbackMode", "fail",
                "intentRoutes", List.of(
                    Map.of("intentCode", "monthly_report", "intentName", "月报", "intentDescription", "处理月报生成", "agentId", "monthly_report")
                ),
                "clusterAgents", List.of(
                    intentAgent("月报智能体", "monthly_report", "处理月报生成", "monthly_output")
                )
            )
        );

        List<WorkflowDraftApi.WorkflowValidationIssue> issues = validator().validateCapabilityReferences(TENANT_ID, USER_ID, List.of(node));

        assertThat(issues).extracting(WorkflowDraftApi.WorkflowValidationIssue::code)
            .contains("WORKFLOW_VALIDATION_CLUSTER_INTENT_INPUT_REQUIRED");
    }

    private WorkflowNodeConfigValidator validator() {
        return new WorkflowNodeConfigValidator(
            systemCapabilityRepository,
            tenantCapabilityGrantRepository,
            assetManagementService,
            runtimeProperties()
        );
    }

    private Map<String, Object> intentAgent(String name, String intentCode, String intentDescription, String output) {
        return Map.ofEntries(
            Map.entry("id", intentCode),
            Map.entry("name", name),
            Map.entry("intentCode", intentCode),
            Map.entry("intentName", name),
            Map.entry("intentDescription", intentDescription),
            Map.entry("systemPromptTemplateId", "none"),
            Map.entry("userPromptTemplateId", "none"),
            Map.entry("systemPrompt", WorkflowPromptDefaults.DEFAULT_SYSTEM_PROMPT),
            Map.entry("userPrompt", WorkflowPromptDefaults.DEFAULT_CLUSTER_USER_PROMPT),
            Map.entry("maxAgentIterationsPerTurn", 4),
            Map.entry("output", output)
        );
    }

    private static AgentRuntimeProperties runtimeProperties() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.setSuggestedIterationsPerTurn(4);
        properties.setMaxIterationsPerTurn(20);
        return properties;
    }
}
