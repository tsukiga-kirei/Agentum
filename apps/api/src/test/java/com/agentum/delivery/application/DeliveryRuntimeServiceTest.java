package com.agentum.delivery.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agentum.delivery.infrastructure.DeliveryRecordRepository;
import com.agentum.system.infrastructure.SystemCapabilityRepository;
import com.agentum.system.infrastructure.TenantCapabilityGrantRepository;
import com.agentum.workflow.domain.WorkflowNodeRunEntity;
import com.agentum.workflow.domain.WorkflowRunEntity;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeliveryRuntimeServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-09T04:00:00Z");
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID OPERATOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");

    private DeliveryRecordRepository deliveryRecordRepository;
    private DeliveryRuntimeService service;

    @BeforeEach
    void setUp() {
        deliveryRecordRepository = mock(DeliveryRecordRepository.class);
        when(deliveryRecordRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new DeliveryRuntimeService(
            mock(SystemCapabilityRepository.class),
            mock(TenantCapabilityGrantRepository.class),
            deliveryRecordRepository,
            mock(EmailDeliveryService.class),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void shouldFallbackToDirectDeliveryWhenCapabilityModeUsesNonePlaceholder() {
        DeliveryRuntimeRequest request = buildRequest(Map.of(
            "deliveryMode", "capability",
            "deliveryCapabilityId", "none",
            "deliveryTarget", "归档 {{risk_summary}}"
        ), Map.of("risk_summary", "授信结论摘要"));

        DeliveryRuntimeResult result = service.execute(request);

        assertThat(result.outputs())
            .containsEntry("deliveryStatus", "success")
            .containsEntry("summary", "已生成站内交付记录：演示任务");
    }

    @Test
    void shouldUseDirectDeliveryWhenModeIsDirect() {
        DeliveryRuntimeRequest request = buildRequest(Map.of("deliveryMode", "direct"), Map.of());

        DeliveryRuntimeResult result = service.execute(request);

        assertThat(result.outputs()).containsEntry("deliveryStatus", "success");
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
