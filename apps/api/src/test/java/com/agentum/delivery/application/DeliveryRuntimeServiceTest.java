package com.agentum.delivery.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agentum.delivery.infrastructure.DeliveryRecordRepository;
import com.agentum.shared.api.ApiException;
import com.agentum.system.domain.SystemCapabilityEntity;
import com.agentum.system.domain.TenantCapabilityGrantEntity;
import com.agentum.system.infrastructure.SystemCapabilityRepository;
import com.agentum.system.infrastructure.TenantCapabilityGrantRepository;
import com.agentum.workflow.domain.WorkflowNodeRunEntity;
import com.agentum.workflow.domain.WorkflowRunEntity;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeliveryRuntimeServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-09T04:00:00Z");
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID OPERATOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");

    private DeliveryRecordRepository deliveryRecordRepository;
    private SystemCapabilityRepository systemCapabilityRepository;
    private TenantCapabilityGrantRepository tenantCapabilityGrantRepository;
    private DocumentDeliveryService documentDeliveryService;
    private DeliveryContentTemplateRenderer contentTemplateRenderer;
    private DeliveryRuntimeService service;

    @BeforeEach
    void setUp() {
        deliveryRecordRepository = mock(DeliveryRecordRepository.class);
        systemCapabilityRepository = mock(SystemCapabilityRepository.class);
        tenantCapabilityGrantRepository = mock(TenantCapabilityGrantRepository.class);
        documentDeliveryService = mock(DocumentDeliveryService.class);
        contentTemplateRenderer = new DeliveryContentTemplateRenderer();
        when(deliveryRecordRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new DeliveryRuntimeService(
            systemCapabilityRepository,
            tenantCapabilityGrantRepository,
            deliveryRecordRepository,
            mock(EmailDeliveryService.class),
            documentDeliveryService,
            contentTemplateRenderer,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void shouldRenderDirectDeliveryWithoutCapabilityAssignment() {
        DeliveryRuntimeRequest request = buildRequest(Map.of(
            "deliveryMode", "direct",
            "deliveryType", "direct",
            "deliveryContent", "# 月报\n\n{{risk_summary}}"
        ), Map.of("risk_summary", "授信通过，建议继续观察。"));

        DeliveryRuntimeResult result = service.execute(request);

        assertThat(result.outputs())
            .containsEntry("deliveryStatus", "success")
            .containsEntry("summary", "# 月报  授信通过，建议继续观察。");
        assertThat(result.outputs().get("deliveryPayload"))
            .isInstanceOf(Map.class)
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .containsEntry("body", "# 月报\n\n授信通过，建议继续观察。");
        assertThat(result.outputs().get("deliveryResult"))
            .isInstanceOf(Map.class)
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .containsEntry("adapter", "direct");
    }

    @Test
    void shouldRejectCapabilityDeliveryWithoutConcreteCapability() {
        DeliveryRuntimeRequest request = buildRequest(Map.of(
            "deliveryMode", "capability",
            "deliveryTarget", "归档 {{risk_summary}}"
        ), Map.of("risk_summary", "授信结论摘要"));

        assertThatThrownBy(() -> service.execute(request))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("请为交付节点配置交付能力");
    }

    @Test
    void shouldDispatchWordDocumentDeliveryCapability() {
        SystemCapabilityEntity capability = SystemCapabilityEntity.create(
            "delivery",
            "Word 文档交付",
            "word_document_delivery",
            "v1",
            "",
            "medium",
            "active",
            Map.of("sourceType", "builtin", "deliveryChannel", "document", "documentKind", "word"),
            NOW
        );
        when(systemCapabilityRepository.findById(capability.getId())).thenReturn(Optional.of(capability));
        when(tenantCapabilityGrantRepository.findByTenantIdAndCapabilityId(TENANT_ID, capability.getId()))
            .thenReturn(Optional.of(TenantCapabilityGrantEntity.create(TENANT_ID, capability.getId(), "enabled", NOW)));
        when(documentDeliveryService.generateRuntimeDocument(any(), any(), any(), any(), any(), any()))
            .thenReturn(Map.of("adapter", "word_document", "fileName", "演示任务.docx"));

        DeliveryRuntimeRequest request = buildRequest(Map.of(
            "deliveryMode", "capability",
            "deliveryCapabilityId", capability.getId().toString(),
            "deliveryType", "word_document",
            "markdownContent", "# {{risk_summary}}",
            "fileNameTemplate", "演示任务.docx"
        ), Map.of("risk_summary", "# 授信结论"));

        DeliveryRuntimeResult result = service.execute(request);

        assertThat(result.outputs())
            .containsEntry("deliveryStatus", "success")
            .containsEntry("summary", "Word 文档已生成：演示任务.docx");
        assertThat(result.outputs().get("deliveryResult"))
            .isInstanceOf(Map.class)
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .containsEntry("adapter", "word_document");
    }

    @Test
    void shouldExecuteOnlyMatchedDeliveryItems() {
        SystemCapabilityEntity capability = SystemCapabilityEntity.create(
            "delivery",
            "Word 文档交付",
            "word_document_delivery",
            "v1",
            "",
            "medium",
            "active",
            Map.of("sourceType", "builtin", "deliveryChannel", "document", "documentKind", "word"),
            NOW
        );
        when(systemCapabilityRepository.findById(capability.getId())).thenReturn(Optional.of(capability));
        when(tenantCapabilityGrantRepository.findByTenantIdAndCapabilityId(TENANT_ID, capability.getId()))
            .thenReturn(Optional.of(TenantCapabilityGrantEntity.create(TENANT_ID, capability.getId(), "enabled", NOW)));
        when(documentDeliveryService.generateRuntimeDocument(any(), any(), any(), any(), any(), any()))
            .thenReturn(Map.of("adapter", "word_document", "fileName", "命中交付.docx"));

        DeliveryRuntimeRequest request = buildRequest(Map.of(
            "deliveryMode", "capability",
            "deliveryConfigMode", "multiple",
            "deliveryExecutionPolicy", "conditional",
            "deliveryItems", List.of(
                Map.of(
                    "id", "contract_report",
                    "name", "合同审查报告",
                    "enabled", true,
                    "triggerRule", Map.of(
                        "type", "cluster_agent_matched",
                        "clusterNodeId", "risk_cluster",
                        "agentId", "contract_agent",
                        "variableName", "contract_agent_output"
                    ),
                    "config", Map.of(
                        "deliveryCapabilityId", capability.getId().toString(),
                        "deliveryType", "word_document",
                        "documentKind", "word",
                        "markdownContent", "# 合同审查\n\n{{contract_agent_output}}",
                        "fileNameTemplate", "合同审查.docx"
                    )
                ),
                Map.of(
                    "id", "finance_report",
                    "name", "财务分析报告",
                    "enabled", true,
                    "triggerRule", Map.of(
                        "type", "cluster_agent_matched",
                        "clusterNodeId", "risk_cluster",
                        "agentId", "finance_agent",
                        "variableName", "finance_agent_output"
                    ),
                    "config", Map.of(
                        "deliveryCapabilityId", capability.getId().toString(),
                        "deliveryType", "word_document",
                        "documentKind", "word",
                        "markdownContent", "# 财务分析\n\n{{finance_agent_output}}",
                        "fileNameTemplate", "财务分析.docx"
                    )
                )
            )
        ), Map.of("contract_agent_output", "合同智能体已执行"));

        DeliveryRuntimeResult result = service.execute(request);

        assertThat(result.outputs())
            .containsEntry("deliveryStatus", "success")
            .containsEntry("summary", "已执行 1 个交付项");
        assertThat(result.outputs().get("deliveryRecords"))
            .isInstanceOf(List.class)
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
            .hasSize(1)
            .first()
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .containsEntry("itemId", "contract_report")
            .containsEntry("itemName", "合同审查报告");
    }

    @Test
    void shouldExecuteMultipleDirectDeliveryItems() {
        DeliveryRuntimeRequest request = buildRequest(Map.of(
            "deliveryMode", "direct",
            "deliveryType", "direct",
            "deliveryConfigMode", "multiple",
            "deliveryExecutionPolicy", "all",
            "deliveryItems", List.of(
                Map.of(
                    "id", "tenant_summary",
                    "name", "租户摘要",
                    "enabled", true,
                    "triggerRule", Map.of("type", "always"),
                    "config", Map.of(
                        "deliveryMode", "direct",
                        "deliveryType", "direct",
                        "deliveryContent", "# 租户摘要\n\n{{tenant_summary}}"
                    )
                ),
                Map.of(
                    "id", "risk_summary",
                    "name", "风险摘要",
                    "enabled", true,
                    "triggerRule", Map.of("type", "always"),
                    "config", Map.of(
                        "deliveryMode", "direct",
                        "deliveryType", "direct",
                        "deliveryContent", "# 风险摘要\n\n{{risk_summary}}"
                    )
                )
            )
        ), Map.of(
            "tenant_summary", "租户管理分配完成。",
            "risk_summary", "未发现高风险项。"
        ));

        DeliveryRuntimeResult result = service.execute(request);

        assertThat(result.outputs())
            .containsEntry("deliveryStatus", "success")
            .containsEntry("summary", "已执行 2 个交付项");
        assertThat(result.outputs().get("deliveryRecords"))
            .isInstanceOf(List.class)
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
            .hasSize(2)
            .extracting(item -> String.valueOf(((Map<?, ?>) item).get("itemId")))
            .containsExactly("tenant_summary", "risk_summary");
    }

    private static DeliveryRuntimeRequest buildRequest(Map<String, Object> config, Map<String, Object> variables) {
        WorkflowRunEntity run = WorkflowRunEntity.create(
            TENANT_ID,
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            "演示任务",
            "演示流程",
            OPERATOR_ID,
            5,
            "RUN-001",
            NOW
        );
        WorkflowNodeRunEntity nodeRun = WorkflowNodeRunEntity.pending(
            run.getId(),
            TENANT_ID,
            run.getWorkflowId(),
            run.getWorkflowVersionId(),
            "delivery_report",
            "delivery",
            "交付结果",
            Map.of(),
            Map.of(),
            config,
            4,
            NOW
        );
        return new DeliveryRuntimeRequest(run, nodeRun, config, variables, OPERATOR_ID);
    }
}
