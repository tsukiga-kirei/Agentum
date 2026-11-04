package com.agentum.delivery.template;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.agentum.asset.application.AssetManagementService;
import com.agentum.delivery.application.*;
import com.agentum.system.domain.SystemCapabilityEntity;
import com.agentum.system.infrastructure.SystemCapabilityRepository;
import com.agentum.workflow.domain.*;
import com.agentum.workflow.infrastructure.WorkflowNodeRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.time.*;
import java.util.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.*;

class DeliveryTemplateServiceTest {
    private final UUID tenant = UUID.randomUUID(), owner = UUID.randomUUID();
    private final DeliveryTemplateRepository repository = mock(DeliveryTemplateRepository.class);
    private final TemplateFileRenderer renderer = new TemplateFileRenderer(new MarkdownDocxRenderer());
    private final ObjectMapper mapper = new ObjectMapper();
    private final DocumentDeliveryStorage storage = mock(DocumentDeliveryStorage.class);
    private final WorkflowNodeRunRepository nodes = mock(WorkflowNodeRunRepository.class);
    private final AssetManagementService assets = mock(AssetManagementService.class);
    private final SystemCapabilityRepository capabilities = mock(SystemCapabilityRepository.class);
    private final Instant now = Instant.parse("2026-10-31T00:00:00Z");
    private final DeliveryTemplateService service = new DeliveryTemplateService(repository, renderer, mapper, storage, nodes, assets, capabilities, Clock.fixed(now, ZoneOffset.UTC), mock(com.agentum.mcp.application.McpDatasetService.class));
    private DeliveryTemplateEntity template;
    @BeforeEach void setup() throws Exception {
        byte[] bytes;
        try (var doc = new XWPFDocument(); var out = new ByteArrayOutputStream()) { doc.createParagraph().createRun().setText("{{summary}}"); doc.write(out); bytes = out.toByteArray(); }
        template = new DeliveryTemplateEntity(); template.id = UUID.randomUUID(); template.templateId = template.id;
        template.tenantId = tenant; template.createdBy = owner; template.name = "风险报告"; template.fileName = "模板.docx";
        template.kind = "word"; template.version = 1; template.status = "draft"; template.readScope = "self"; template.updatedAt = now;
        template.fileBytes = bytes; template.spec = mapper.convertValue(renderer.inspect(bytes, "word"), Map.class);
        when(repository.findByIdAndTenantId(template.id, tenant)).thenReturn(Optional.of(template));
    }
    @Test void rejectsOtherTenantAndPrivateAccessAndKeepsDraftPrivate() {
        assertThatThrownBy(() -> service.get(UUID.randomUUID(), owner, template.id)).hasMessageContaining("不存在");
        assertThatThrownBy(() -> service.get(tenant, UUID.randomUUID(), template.id)).hasMessageContaining("权限");
        template.readScope = "all";
        assertThatThrownBy(() -> service.get(tenant, UUID.randomUUID(), template.id)).hasMessageContaining("权限");
        template.status = "published"; assertThat(service.get(tenant, UUID.randomUUID(), template.id).name()).isEqualTo("风险报告");
    }
    @Test void publicationFreezesOriginalAndSpecAndCopyCreatesNextVersion() {
        service.publish(tenant, owner, template.id);
        var spec = service.get(tenant, owner, template.id).spec();
        assertThatThrownBy(() -> service.save(tenant, owner, template.id, new TemplateModels.Save("修改", "self", spec))).hasMessageContaining("不可修改");
        assertThatThrownBy(() -> service.replaceFile(tenant, owner, template.id, "新.docx", template.fileBytes)).hasMessageContaining("草稿");
        when(repository.lockVersion(template.id, tenant)).thenReturn(Optional.of(template));
        when(repository.findFirstByTemplateIdOrderByVersionDesc(template.id)).thenReturn(Optional.of(template));
        var copy = service.copy(tenant, owner, template.id);
        assertThat(copy.templateId()).isEqualTo(template.id); assertThat(copy.version()).isEqualTo(2); assertThat(copy.status()).isEqualTo("draft");
        assertThat(template.status).isEqualTo("published");
    }
    @Test void rejectsUnpublishedReferenceAndInvalidStylesAndUnknownFields() {
        Map<String, Object> config = Map.of("templateVersionId", template.id.toString(), "documentKind", "word", "templateBindings", List.of());
        assertThatThrownBy(() -> service.validateReference(tenant, owner, config)).hasMessageContaining("已发布");
        template.status = "published";
        assertThatThrownBy(() -> service.validateReference(tenant, owner, config)).hasMessageContaining("尚未绑定");
        assertThatThrownBy(() -> service.validateStyle(Map.of("color", "url(bad)"))).hasMessageContaining("颜色");
        assertThatThrownBy(() -> service.validateStyle(Map.of("columnWidth", 1000))).hasMessageContaining("范围");
    }
    @Test void assemblesTwoAgentsByNodeIdentityAndDoesNotUseShadowedGlobalValue() throws Exception {
        template.status = "published";
        var capability = SystemCapabilityEntity.create("delivery", "Word", "word", "v1", "", "low", "active", Map.of("documentKind", "word", "retentionPolicy", "permanent"), now);
        when(capabilities.findById(capability.getId())).thenReturn(Optional.of(capability));
        when(assets.canUseSystemCapabilityReference(tenant, owner, capability.getId(), "delivery")).thenReturn(true);
        var run = WorkflowRunEntity.create(tenant, UUID.randomUUID(), UUID.randomUUID(), 1, "测试", "流程", owner, 4, "RUN-1", now);
        var a = node(run, "a", 1); a.complete(Map.of("data", Map.of("summary", "来自 A")), now);
        var b = node(run, "b", 2); b.complete(Map.of("data", Map.of("summary", "来自 B")), now);
        var delivery = node(run, "delivery", 3);
        when(nodes.findByRunIdOrderBySortOrderAsc(run.getId())).thenReturn(List.of(a, b, delivery));
        Map<String, Object> binding = Map.of("field", "summary", "sourceNodeId", "a", "variable", "data", "path", "summary", "constant", false, "additionalSources", List.of(Map.of("sourceNodeId", "b", "variable", "data", "path", "summary")));
        Map<String, Object> config = Map.of("generationMode", "file_template", "templateVersionId", template.id.toString(), "documentKind", "word", "templateBindings", List.of(binding));
        when(storage.store(eq(tenant), any(), anyString(), anyString(), any())).thenAnswer(call -> {
            byte[] bytes = call.getArgument(4);
            try (var doc = new XWPFDocument(new ByteArrayInputStream(bytes))) { assertThat(doc.getParagraphs().getFirst().getText()).isEqualTo("来自 A\n\n来自 B"); }
            return new DocumentDeliveryArtifact("报告.docx", "safe-key", MarkdownDocxRenderer.DOCX_CONTENT_TYPE, bytes.length);
        });
        var result = service.generate(new DeliveryRuntimeRequest(run, delivery, config, Map.of(), owner), UUID.randomUUID(), capability, config, Map.of("data", "不能使用这个覆盖值"));
        assertThat(result).containsEntry("templateVersionId", template.id.toString()).containsEntry("retentionPolicy", "permanent");
        b.resetToPending(now);
        assertThatThrownBy(() -> service.generate(new DeliveryRuntimeRequest(run, delivery, config, Map.of(), owner), UUID.randomUUID(), capability, config, Map.of())).hasMessageContaining("尚未完成");
    }
    private WorkflowNodeRunEntity node(WorkflowRunEntity run, String key, int order) { return WorkflowNodeRunEntity.pending(run.getId(), tenant, run.getWorkflowId(), run.getWorkflowVersionId(), key, "agent", key, Map.of(), Map.of(), Map.of(), order, now); }
}
