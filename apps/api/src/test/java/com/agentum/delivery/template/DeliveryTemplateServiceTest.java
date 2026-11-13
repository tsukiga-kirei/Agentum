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
    @Test void savingPublishedTemplateCreatesRevisionWhileCopyCreatesIndependentResource() {
        service.publish(tenant, owner, template.id);
        var spec = service.get(tenant, owner, template.id).spec();
        when(repository.lockVersion(template.id, tenant)).thenReturn(Optional.of(template));
        when(repository.findFirstByTemplateIdOrderByVersionDesc(template.id)).thenReturn(Optional.of(template));
        // 无改动不会制造新版本；真实修改保存到新草稿，既有发布内容不变。
        assertThat(service.save(tenant, owner, template.id, new TemplateModels.Save(template.name, "self", spec)).id()).isEqualTo(template.id);
        var revision = service.save(tenant, owner, template.id, new TemplateModels.Save("修改", "self", spec));
        assertThat(revision.id()).isNotEqualTo(template.id);
        assertThat(revision.templateId()).isEqualTo(template.templateId);
        assertThat(revision.version()).isEqualTo(2);
        assertThat(revision.status()).isEqualTo("draft");
        assertThat(template.name).isEqualTo("风险报告");
        var copy = service.copy(tenant, owner, template.id);
        assertThat(copy.templateId()).isEqualTo(copy.id()).isNotEqualTo(template.id);
        assertThat(copy.version()).isEqualTo(1); assertThat(copy.status()).isEqualTo("draft");
        assertThat(copy.name()).isEqualTo("风险报告 副本");
        assertThat(template.status).isEqualTo("published");
    }
    @Test void numericallyEquivalentStylesDoNotCreateEmptyRevision() {
        var spec = service.get(tenant, owner, template.id).spec();
        var current = new TemplateModels.Spec(spec.fields(), spec.locations(), Map.of("fontSize", 11.0), spec.sheetStyles());
        service.save(tenant, owner, template.id, new TemplateModels.Save(template.name, "self", current));
        service.publish(tenant, owner, template.id);
        var equivalent = new TemplateModels.Spec(spec.fields(), spec.locations(), Map.of("fontSize", 11), spec.sheetStyles());
        assertThat(service.save(tenant, owner, template.id, new TemplateModels.Save(template.name, "self", equivalent)).id()).isEqualTo(template.id);
        verify(repository, never()).lockVersion(any(), any());
    }
    @Test void replacementCreatesRevisionAndExistingDraftPreventsAccidentalOverwrite() {
        service.publish(tenant, owner, template.id);
        when(repository.lockVersion(template.id, tenant)).thenReturn(Optional.of(template));
        var replaced = service.replaceFile(tenant, owner, template.id, "新版.docx", template.fileBytes);
        assertThat(replaced.id()).isNotEqualTo(template.id);
        assertThat(replaced.version()).isEqualTo(2); assertThat(template.fileName).isEqualTo("模板.docx");
        when(repository.findFirstByTemplateIdAndStatusAndDeletedAtIsNullOrderByVersionDesc(template.templateId, "draft")).thenReturn(Optional.of(template));
        var spec = service.get(tenant, owner, template.id).spec();
        assertThatThrownBy(() -> service.save(tenant, owner, template.id, new TemplateModels.Save("不能覆盖草稿", "self", spec))).hasMessageContaining("已有未发布草稿");
    }
    @Test void deletionIsTenantAndOwnerProtectedKeepsPinnedVersionAndRejectsFurtherEdits() {
        template.status = "published"; template.readScope = "all";
        when(repository.lockVersion(template.id, tenant)).thenReturn(Optional.of(template));
        when(repository.findByTemplateIdAndTenantId(template.templateId, tenant)).thenReturn(List.of(template));
        assertThatThrownBy(() -> service.delete(UUID.randomUUID(), owner, template.id)).hasMessageContaining("不存在");
        assertThatThrownBy(() -> service.delete(tenant, UUID.randomUUID(), template.id)).hasMessageContaining("只有创建者");
        service.delete(tenant, owner, template.id);
        assertThat(template.deletedAt).isEqualTo(now); assertThat(template.status).isEqualTo("published");
        assertThat(service.get(tenant, owner, template.id).deleted()).isTrue();
        assertThat(service.original(tenant, owner, template.id).bytes()).isEqualTo(template.fileBytes);
        service.validateReference(tenant, owner, Map.of("templateVersionId", template.id.toString(), "documentKind", "word",
            "templateBindings", List.of(Map.of("field", "summary", "constant", true, "value", "保留引用"))));
        assertThatThrownBy(() -> service.save(tenant, owner, template.id, new TemplateModels.Save("修改", "self", service.get(tenant, owner, template.id).spec()))).hasMessageContaining("已从库中删除");
        clearInvocations(repository); service.delete(tenant, owner, template.id); verify(repository, never()).save(any());
    }
    @Test void deletionIncludesAllVersionsAndHistoryChecksResourceAccess() {
        var next = new DeliveryTemplateEntity(); next.id = UUID.randomUUID();
        when(repository.lockVersion(template.id, tenant)).thenReturn(Optional.of(template));
        when(repository.findByTemplateIdAndTenantId(template.templateId, tenant)).thenReturn(List.of(template, next));
        service.delete(tenant, owner, template.id);
        assertThat(next.deletedAt).isEqualTo(now);
        assertThatThrownBy(() -> service.versions(tenant, UUID.randomUUID(), template.id, 1, 10)).hasMessageContaining("权限");
        assertThatThrownBy(() -> service.versions(tenant, owner, template.id, 0, 10)).hasMessageContaining("分页");
    }
    @Test void fileNamesResolveScalarsAndNestedFieldsWithoutReinterpretingTheirContents() {
        var variables = Map.<String, Object>of("report_year", "2026-10", "meta", Map.of("data_date", "2026年10月31日"), "literal", "{{runNumber}}", "count", 0, "ok", false);
        assertThat(DeliveryTemplateService.renderFileName("月报-{{ report_year }}-{{meta.data_date}}-{{count}}-{{ok}}.xlsx", variables))
            .isEqualTo("月报-2026-10-2026年10月31日-0-false.xlsx");
        assertThat(DeliveryTemplateService.renderFileName("{{literal}}", variables)).isEqualTo("{{runNumber}}");
        for (String name : List.of("missing", "meta", "meta.missing"))
            assertThatThrownBy(() -> DeliveryTemplateService.renderFileName("{{" + name + "}}", variables)).hasMessageContaining("文件名变量缺失");
        assertThat(DeliveryTemplateService.renderFileName("固定名称.xlsx", Map.of())).isEqualTo("固定名称.xlsx");
    }
    @Test void rejectsUnpublishedReferenceAndInvalidStylesAndUnknownFields() {
        Map<String, Object> config = Map.of("templateVersionId", template.id.toString(), "documentKind", "word", "templateBindings", List.of());
        assertThatThrownBy(() -> service.validateReference(tenant, owner, config)).hasMessageContaining("已发布");
        template.status = "published";
        assertThatThrownBy(() -> service.validateReference(tenant, owner, config)).hasMessageContaining("尚未绑定");
        assertThatThrownBy(() -> service.validateStyle(Map.of("color", "url(bad)"))).hasMessageContaining("颜色");
        assertThatThrownBy(() -> service.validateStyle(Map.of("columnWidth", 1000))).hasMessageContaining("范围");
    }
    @Test void savesFormatSchemesAndRejectsInvalidSchemeStylesBeforePublishing() {
        var base = service.get(tenant, owner, template.id).spec();
        var group = new TemplateModels.FormatGroup("text", "文字", "text", List.of("summary"), Map.of("numberFormat", "@"));
        var spec = new TemplateModels.Spec(base.fields(), base.locations(), base.defaultStyle(), base.sheetStyles(), base.excelRegions(), List.of(group));
        var saved = service.save(tenant, owner, template.id, new TemplateModels.Save(template.name, "self", spec));
        assertThat(saved.spec().formatGroups()).containsExactly(group);
        var invalid = new TemplateModels.FormatGroup("text", "文字", "text", List.of("summary"), Map.of("color", "red"));
        var wrong = new TemplateModels.Spec(base.fields(), base.locations(), base.defaultStyle(), base.sheetStyles(), base.excelRegions(), List.of(invalid));
        assertThatThrownBy(() -> service.save(tenant, owner, template.id, new TemplateModels.Save(template.name, "self", wrong))).hasMessageContaining("颜色");
        assertThat(service.publish(tenant, owner, template.id).spec().formatGroups()).containsExactly(group);
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
            sheet.createRow(1).createCell(0).setCellValue("{{items[].start_date}}");
            sheet.getRow(0).createCell(1).setCellValue("金额"); sheet.getRow(1).createCell(1).setCellValue("{{items[].amount}}");
            var repeat = book.createSheet("重复金额"); repeat.createRow(0).createCell(0).setCellValue("重复金额"); repeat.createRow(1).createCell(0).setCellValue("{{items[].amount}}");
            book.write(out); template.fileBytes = out.toByteArray();
        }
        template.kind = "excel"; template.fileName = "日期.xlsx"; template.status = "published";
        var inspected = renderer.inspect(template.fileBytes, "excel");
        var date = new TemplateModels.Field("start_date", "开始日期", "date", false, 100, 200, "", List.of());
        var amount = new TemplateModels.Field("amount", "金额", "number", false, 100, 200, "", List.of());
        var table = new TemplateModels.Field("items", "日期明细", "table", true, 20000, 100000, "", List.of(date, amount));
        template.spec = mapper.convertValue(new TemplateModels.Spec(List.of(table), inspected.locations(), Map.of(), Map.of(), inspected.excelRegions(), List.of(new TemplateModels.FormatGroup("amount", "金额", "amount", List.of("items.amount"), Map.of("numberFormat", "#,##0.00")))), Map.class);
        var capability = SystemCapabilityEntity.create("delivery", "Excel", "excel", "v1", "", "low", "active", Map.of("documentKind", "excel", "retentionPolicy", "permanent"), now);
        when(capabilities.findById(capability.getId())).thenReturn(Optional.of(capability));
        when(assets.canUseSystemCapabilityReference(tenant, owner, capability.getId(), "delivery")).thenReturn(true);
        var run = WorkflowRunEntity.create(tenant, UUID.randomUUID(), UUID.randomUUID(), 1, "测试", "流程", owner, 2, "RUN-DATE", now);
        Map<String, Object> reference = Map.of("datasetId", UUID.randomUUID().toString());
        var source = node(run, "source", 1); source.complete(Map.of("mcp_datasets", Map.of("dates", reference)), now);
        var delivery = node(run, "delivery", 2);
        when(nodes.findByRunIdOrderBySortOrderAsc(run.getId())).thenReturn(List.of(source, delivery));
        Map<String, Object> binding = Map.of("field", "items", "sourceNodeId", "source", "variable", "mcp_datasets", "path", "dates", "constant", false, "columnMappings", Map.of("start_date", "raw_date", "amount", "raw_amount"));
        Map<String, Object> config = Map.of("templateVersionId", template.id.toString(), "documentKind", "excel", "templateBindings", List.of(binding));
        Map<String, Object> missing = new LinkedHashMap<>(); missing.put("raw_date", null);
        List<Map<String, Object>> rows = List.of(Map.of("raw_date", "20260513", "raw_amount", "1234.567890000000"), missing, Map.of("raw_date", "2026-05-14", "raw_amount", "0.000000000000"), Map.of("raw_date", 20240229, "raw_amount", "10.000000000000"));
        when(datasets.readRows(eq(tenant), eq(run.getId()), eq(source.getId()), eq(owner), eq(reference))).thenReturn(rows);
        when(storage.store(eq(tenant), any(), anyString(), anyString(), any())).thenAnswer(call -> {
            byte[] bytes = call.getArgument(4);
            try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                var sheet = book.getSheetAt(0);
                assertThat(sheet.getRow(1).getCell(0).getLocalDateTimeCellValue().toLocalDate()).isEqualTo(LocalDate.of(2026, 5, 13));
                assertThat(sheet.getRow(2).getCell(0).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.BLANK);
                assertThat(sheet.getRow(3).getCell(0).getLocalDateTimeCellValue().toLocalDate()).isEqualTo(LocalDate.of(2026, 5, 14));
                assertThat(sheet.getRow(4).getCell(0).getLocalDateTimeCellValue().toLocalDate()).isEqualTo(LocalDate.of(2024, 2, 29));
                assertThat(sheet.getRow(1).getCell(1).getNumericCellValue()).isCloseTo(1234.56789, within(0.0000001));
                assertThat(sheet.getRow(2).getCell(1).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.BLANK);
                assertThat(sheet.getRow(3).getCell(1).getNumericCellValue()).isZero();
                assertThat(sheet.getRow(1).getCell(1).getCellStyle().getDataFormatString()).isEqualTo("#,##0.00");
                assertThat(book.getSheet("重复金额").getRow(1).getCell(0).getCellStyle().getDataFormatString()).isEqualTo("#,##0.00");
            }
            return new DocumentDeliveryArtifact("日期.xlsx", "safe-key", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes.length);
        });
        var request = new DeliveryRuntimeRequest(run, delivery, config, Map.of(), owner);
        service.generate(request, UUID.randomUUID(), capability, config, Map.of());
        assertThat(rows.getFirst().get("raw_date")).isEqualTo("20260513");
        for (String invalid : List.of("20260230", "2026-02-30", "bad-date")) {
            when(datasets.readRows(eq(tenant), eq(run.getId()), eq(source.getId()), eq(owner), eq(reference))).thenReturn(List.of(Map.of("raw_date", invalid)));
            assertThatThrownBy(() -> service.generate(request, UUID.randomUUID(), capability, config, Map.of())).hasMessageContaining("有效的 yyyy-MM-dd 或 yyyyMMdd");
        }
        verify(storage, times(1)).store(eq(tenant), any(), anyString(), anyString(), any());
    }
    private WorkflowNodeRunEntity node(WorkflowRunEntity run, String key, int order) { return WorkflowNodeRunEntity.pending(run.getId(), tenant, run.getWorkflowId(), run.getWorkflowVersionId(), key, "agent", key, Map.of(), Map.of(), Map.of(), order, now); }
}
