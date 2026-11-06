package com.agentum.mcp.application;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.agentum.attachment.application.AttachmentObjectStorage;
import com.agentum.mcp.domain.McpDatasetEntity;
import com.agentum.mcp.infrastructure.McpDatasetRepository;
import com.agentum.asset.application.AssetManagementService;
import com.agentum.workflow.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class McpDatasetServiceTest {
    private final McpDatasetRepository repository = mock(McpDatasetRepository.class);
    private final AttachmentObjectStorage storage = mock(AttachmentObjectStorage.class);
    private final AssetManagementService assets = mock(AssetManagementService.class);
    private final Instant now = Instant.parse("2026-11-04T00:00:00Z");
    private final McpDatasetService service = new McpDatasetService(repository, storage, new ObjectMapper(), Clock.fixed(now, ZoneOffset.UTC), assets);
    @Test void persistsFullRowsButOnlyExposesWhitelistedPreviewAndEnforcesRunAndSource() {
        UUID tenant = UUID.randomUUID(), runId = UUID.randomUUID(), nodeId = UUID.randomUUID(), cap = UUID.randomUUID(), user = UUID.randomUUID();
        var run = mock(WorkflowRunEntity.class); var node = mock(WorkflowNodeRunEntity.class);
        when(run.getTenantId()).thenReturn(tenant); when(run.getId()).thenReturn(runId); when(node.getId()).thenReturn(nodeId);
        Map<String, byte[]> objects = new HashMap<>(); Map<UUID, McpDatasetEntity> records = new HashMap<>();
        when(storage.storeOriginal(any(), any(), any(), any(), any())).thenAnswer(inv -> { String key = inv.getArgument(1).toString(); objects.put(key, inv.getArgument(4)); return key; });
        when(storage.read(any())).thenAnswer(inv -> objects.get(inv.getArgument(0)));
        when(repository.save(any())).thenAnswer(inv -> { McpDatasetEntity e = inv.getArgument(0); records.put(e.id, e); return e; });
        when(repository.findByIdAndTenantIdAndRunId(any(), any(), any())).thenAnswer(inv -> Optional.ofNullable(records.get(inv.getArgument(0))).filter(e -> e.tenantId.equals(inv.getArgument(1)) && e.runId.equals(inv.getArgument(2))));
        when(assets.canUseSystemCapabilityReference(tenant, user, cap, "mcp")).thenReturn(true);
        var request = new McpRuntimeRequest(run, node, Map.of(), Map.of(), user);
        var response = service.save(request, cap, UUID.randomUUID(), "credit", List.of(Map.of("no", "001", "private", "hidden"), Map.of("no", "002", "private", "secret")), Map.of("fields", List.of("no"), "preview", "head", "previewRows", 1));
        assertThat(response.toString()).doesNotContain("hidden", "secret", "private");
        assertThat(service.readRows(tenant, runId, nodeId, user, response)).hasSize(2);
        assertThat(service.readRows(tenant, runId, nodeId, user, response).getFirst()).containsEntry("private", "hidden");
        when(repository.findByNodeRunIdOrderByCreatedAtAsc(nodeId)).thenReturn(new ArrayList<>(records.values()));
        var refs = service.references(nodeId, records.values().stream().map(e -> e.callLogId.toString()).collect(java.util.stream.Collectors.toSet()));
        assertThat(refs).containsKey("cap_" + cap.toString().replace('-', '_'));
        assertThat(service.references(nodeId, Set.of())).isEmpty();
        var page = service.query(request, Set.of(cap), Map.of("datasetId", response.get("datasetId"), "limit", 1));
        assertThat(page).containsEntry("hasMore", true); assertThat(page.toString()).doesNotContain("hidden", "secret");
        assertThatThrownBy(() -> service.query(request, Set.of(cap), Map.of("datasetId", response.get("datasetId"), "fields", List.of("private")))).hasMessageContaining("可见范围");
        assertThatThrownBy(() -> service.readRows(tenant, UUID.randomUUID(), nodeId, user, response)).hasMessageContaining("本次运行");
        assertThatThrownBy(() -> service.readRows(tenant, runId, UUID.randomUUID(), user, response)).hasMessageContaining("来源节点");
        assertThatThrownBy(() -> service.query(request, Set.of(), Map.of("datasetId", response.get("datasetId")))).hasMessageContaining("未授权");
        // 超过原七天期限也不丢失仍属于流程的结果；仅删除流程后回收对象。
        var later = new McpDatasetService(repository, storage, new ObjectMapper(), Clock.fixed(now.plus(Duration.ofDays(400)), ZoneOffset.UTC), assets);
        assertThat(later.readRows(tenant, runId, nodeId, user, response)).hasSize(2);
        assertThat(response).doesNotContainKey("expiresAt");
        when(repository.findOrphanedRunResults()).thenReturn(List.of());
        later.cleanup(); verify(repository, never()).delete(any());
        when(repository.findOrphanedRunResults()).thenReturn(new ArrayList<>(records.values()));
        later.cleanup(); verify(repository).delete(any()); verify(storage).delete(any());
    }
}
