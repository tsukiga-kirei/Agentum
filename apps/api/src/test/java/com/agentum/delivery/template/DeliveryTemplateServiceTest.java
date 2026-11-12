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
    private final com.agentum.mcp.application.McpDatasetService datasets = mock(com.agentum.mcp.application.McpDatasetService.class);
    private final Instant now = Instant.parse("2026-10-31T00:00:00Z");
    private final DeliveryTemplateService service = new DeliveryTemplateService(repository, renderer, mapper, storage, nodes, assets, capabilities, Clock.fixed(now, ZoneOffset.UTC), datasets);
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
    @Test void bindsOneAgentByNodeIdentityAndDoesNotUseShadowedGlobalValue() throws Exception {
        template.status = "published";
        var capability = SystemCapabilityEntity.create("delivery", "Word", "word", "v1", "", "low", "active", Map.of("documentKind", "word", "retentionPolicy", "permanent"), now);
        when(capabilities.findById(capability.getId())).thenReturn(Optional.of(capability));
        when(assets.canUseSystemCapabilityReference(tenant, owner, capability.getId(), "delivery")).thenReturn(true);
        var run = WorkflowRunEntity.create(tenant, UUID.randomUUID(), UUID.randomUUID(), 1, "测试", "流程", owner, 4, "RUN-1", now);
        var a = node(run, "a", 1); a.complete(Map.of("data", Map.of("summary", "来自 A")), now);
        var b = node(run, "b", 2); b.complete(Map.of("data", Map.of("summary", "来自 B")), now);
        var delivery = node(run, "delivery", 3);
        when(nodes.findByRunIdOrderBySortOrderAsc(run.getId())).thenReturn(List.of(a, b, delivery));
        Map<String, Object> binding = Map.of("field", "summary", "sourceNodeId", "a", "variable", "data", "path", "summary", "constant", false);
        Map<String, Object> config = Map.of("generationMode", "file_template", "templateVersionId", template.id.toString(), "documentKind", "word", "templateBindings", List.of(binding));
        when(storage.store(eq(tenant), any(), anyString(), anyString(), any())).thenAnswer(call -> {
            byte[] bytes = call.getArgument(4);
            try (var doc = new XWPFDocument(new ByteArrayInputStream(bytes))) { assertThat(doc.getParagraphs().getFirst().getText()).isEqualTo("来自 A"); }
            return new DocumentDeliveryArtifact("报告.docx", "safe-key", MarkdownDocxRenderer.DOCX_CONTENT_TYPE, bytes.length);
        });
        var result = service.generate(new DeliveryRuntimeRequest(run, delivery, config, Map.of(), owner), UUID.randomUUID(), capability, config, Map.of("data", "不能使用这个覆盖值"));
        assertThat(result).containsEntry("templateVersionId", template.id.toString()).containsEntry("retentionPolicy", "permanent");
        a.resetToPending(now);
        assertThatThrownBy(() -> service.generate(new DeliveryRuntimeRequest(run, delivery, config, Map.of(), owner), UUID.randomUUID(), capability, config, Map.of())).hasMessageContaining("尚未完成");
    }
    @Test void datasetDatesAcceptBothDocumentedFormatsPreserveEmptySlotsAndRejectInvalidDates() throws Exception {
        try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("日期"); sheet.createRow(0).createCell(0).setCellValue("开始日期");
            sheet.createRow(1).createCell(0).setCellValue("{{items[].start_date}}"); book.write(out); template.fileBytes = out.toByteArray();
        }
        template.kind = "excel"; template.fileName = "日期.xlsx"; template.status = "published";
        var inspected = renderer.inspect(template.fileBytes, "excel");
        var date = new TemplateModels.Field("start_date", "开始日期", "date", false, 100, 200, "", List.of());
        var table = new TemplateModels.Field("items", "日期明细", "table", true, 20000, 100000, "", List.of(date));
        template.spec = mapper.convertValue(new TemplateModels.Spec(List.of(table), inspected.locations(), Map.of(), Map.of(), inspected.excelRegions()), Map.class);
        var capability = SystemCapabilityEntity.create("delivery", "Excel", "excel", "v1", "", "low", "active", Map.of("documentKind", "excel", "retentionPolicy", "permanent"), now);
        when(capabilities.findById(capability.getId())).thenReturn(Optional.of(capability));
        when(assets.canUseSystemCapabilityReference(tenant, owner, capability.getId(), "delivery")).thenReturn(true);
        var run = WorkflowRunEntity.create(tenant, UUID.randomUUID(), UUID.randomUUID(), 1, "测试", "流程", owner, 2, "RUN-DATE", now);
        Map<String, Object> reference = Map.of("datasetId", UUID.randomUUID().toString());
        var source = node(run, "source", 1); source.complete(Map.of("mcp_datasets", Map.of("dates", reference)), now);
        var delivery = node(run, "delivery", 2);
        when(nodes.findByRunIdOrderBySortOrderAsc(run.getId())).thenReturn(List.of(source, delivery));
        Map<String, Object> binding = Map.of("field", "items", "sourceNodeId", "source", "variable", "mcp_datasets", "path", "dates", "constant", false);
        Map<String, Object> config = Map.of("templateVersionId", template.id.toString(), "documentKind", "excel", "templateBindings", List.of(binding));
        Map<String, Object> missing = new LinkedHashMap<>(); missing.put("start_date", null);
        List<Map<String, Object>> rows = List.of(Map.of("start_date", "20260513"), missing, Map.of("start_date", "2026-05-14"), Map.of("start_date", 20240229));
        when(datasets.readRows(eq(tenant), eq(run.getId()), eq(source.getId()), eq(owner), eq(reference))).thenReturn(rows);
        when(storage.store(eq(tenant), any(), anyString(), anyString(), any())).thenAnswer(call -> {
            byte[] bytes = call.getArgument(4);
            try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                var sheet = book.getSheetAt(0);
                assertThat(sheet.getRow(1).getCell(0).getLocalDateTimeCellValue().toLocalDate()).isEqualTo(LocalDate.of(2026, 5, 13));
                assertThat(sheet.getRow(2).getCell(0).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.BLANK);
                assertThat(sheet.getRow(3).getCell(0).getLocalDateTimeCellValue().toLocalDate()).isEqualTo(LocalDate.of(2026, 5, 14));
                assertThat(sheet.getRow(4).getCell(0).getLocalDateTimeCellValue().toLocalDate()).isEqualTo(LocalDate.of(2024, 2, 29));
            }
            return new DocumentDeliveryArtifact("日期.xlsx", "safe-key", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes.length);
        });
        var request = new DeliveryRuntimeRequest(run, delivery, config, Map.of(), owner);
        service.generate(request, UUID.randomUUID(), capability, config, Map.of());
        assertThat(rows.getFirst().get("start_date")).isEqualTo("20260513");
        for (String invalid : List.of("20260230", "2026-02-30", "bad-date")) {
            when(datasets.readRows(eq(tenant), eq(run.getId()), eq(source.getId()), eq(owner), eq(reference))).thenReturn(List.of(Map.of("start_date", invalid)));
            assertThatThrownBy(() -> service.generate(request, UUID.randomUUID(), capability, config, Map.of())).hasMessageContaining("有效的 yyyy-MM-dd 或 yyyyMMdd");
        }
        verify(storage, times(1)).store(eq(tenant), any(), anyString(), anyString(), any());
    }
    private WorkflowNodeRunEntity node(WorkflowRunEntity run, String key, int order) { return WorkflowNodeRunEntity.pending(run.getId(), tenant, run.getWorkflowId(), run.getWorkflowVersionId(), key, "agent", key, Map.of(), Map.of(), Map.of(), order, now); }
}
