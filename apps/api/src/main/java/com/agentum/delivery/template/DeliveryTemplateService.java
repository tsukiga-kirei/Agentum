package com.agentum.delivery.template;

import com.agentum.asset.application.AssetManagementService;
import com.agentum.delivery.application.*;
import com.agentum.shared.api.*;
import com.agentum.shared.pagination.PageResponse;
import com.agentum.system.domain.SystemCapabilityEntity;
import com.agentum.system.infrastructure.SystemCapabilityRepository;
import com.agentum.workflow.infrastructure.WorkflowNodeRunRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.*;
import org.slf4j.*;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 模板版本、资源访问及确定性数据组装；原有交付文本解析器不参与此路径。 */
@Service
public class DeliveryTemplateService {
    private static final Logger log = LoggerFactory.getLogger(DeliveryTemplateService.class);
    private final DeliveryTemplateRepository repository;
    private final TemplateFileRenderer renderer;
    private final ObjectMapper mapper;
    private final DocumentDeliveryStorage storage;
    private final WorkflowNodeRunRepository nodes;
    private final AssetManagementService assets;
    private final SystemCapabilityRepository capabilities;
    private final Clock clock;
    private final com.agentum.mcp.application.McpDatasetService datasets;
    public DeliveryTemplateService(DeliveryTemplateRepository repository, TemplateFileRenderer renderer, ObjectMapper mapper,
        DocumentDeliveryStorage storage, WorkflowNodeRunRepository nodes, AssetManagementService assets,
        SystemCapabilityRepository capabilities, Clock clock, com.agentum.mcp.application.McpDatasetService datasets) {
        this.repository = repository; this.renderer = renderer; this.mapper = mapper; this.storage = storage;
        this.nodes = nodes; this.assets = assets; this.capabilities = capabilities; this.clock = clock; this.datasets = datasets;
    }
    @Transactional(readOnly = true)
    public PageResponse<TemplateModels.Row> list(UUID tenant, UUID user, int page, int size, boolean published, String keyword) {
        if (page < 1 || size < 1 || size > 100) throw StructuredContent.error("分页参数不正确");
        return PageResponse.from(repository.search(tenant, user, published, keyword, PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "updatedAt"))).map(this::row));
    }
    @Transactional(readOnly = true)
    public TemplateModels.Row get(UUID tenant, UUID user, UUID id) { return row(read(tenant, user, id, false)); }
    @Transactional(readOnly = true)
    public PageResponse<TemplateModels.Row> versions(UUID tenant, UUID user, UUID id, int page, int size) {
        if (page < 1 || size < 1 || size > 100) throw StructuredContent.error("分页参数不正确");
        var entity = read(tenant, user, id, false);
        return PageResponse.from(repository.history(entity.templateId, tenant, user,
            PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "version"))).map(this::row));
    }
    @Transactional
    public void delete(UUID tenant, UUID user, UUID id) {
        var entity = read(tenant, user, id, false);
        // 只删除创建者自己的库中版本；保留原件和不可变发布版本供已有流程、重试和审计读取。
        if (!entity.createdBy.equals(user)) {
            event("模板删除被拒绝", entity, user);
            throw new ApiException(HttpStatus.FORBIDDEN, "DELIVERY_TEMPLATE_EDIT_DENIED", "只有创建者可以删除模板");
        }
        if (entity.deletedAt != null) return;
        repository.lockVersion(entity.templateId, tenant).orElseThrow(() -> StructuredContent.error("模板不存在"));
        // 库中以一个模板展示，删除其整条版本链；固定引用仍按版本读取，不受列表过滤影响。
        for (var version : repository.findByTemplateIdAndTenantId(entity.templateId, tenant)) {
            version.deletedAt = clock.instant(); version.updatedAt = clock.instant(); repository.save(version);
        }
        event("从模板库删除模板及版本", entity, user);
    }
    @Transactional
    public TemplateModels.Row upload(UUID tenant, UUID user, String name, String fileName, byte[] bytes) {
        if (bytes.length == 0 || bytes.length > 20 * 1024 * 1024) throw StructuredContent.error("模板文件应为 1 字节～20 MB");
        String lower = fileName.toLowerCase(Locale.ROOT);
        String kind = lower.endsWith(".docx") ? "word" : lower.endsWith(".xlsx") ? "excel" : "";
        if (kind.isEmpty()) throw StructuredContent.error("仅支持 docx、xlsx 模板，暂不支持宏和加密文件");
        var spec = renderer.inspect(bytes, kind);
        var entity = new DeliveryTemplateEntity();
        entity.id = UUID.randomUUID(); entity.templateId = entity.id; entity.tenantId = tenant;
        entity.name = validName(name); entity.fileName = DocumentDeliveryStorage.sanitizeFileName(fileName, "模板" + ("word".equals(kind) ? ".docx" : ".xlsx"), "word".equals(kind) ? ".docx" : ".xlsx");
        entity.kind = kind; entity.version = 1; entity.status = "draft"; entity.readScope = "self";
        entity.createdBy = user; entity.updatedAt = clock.instant(); entity.fileBytes = bytes;
        entity.spec = mapper.convertValue(spec, new TypeReference<>() {});
        repository.save(entity); event("上传模板草稿", entity, user);
        return row(entity);
    }
    @Transactional
    public TemplateModels.Row save(UUID tenant, UUID user, UUID id, TemplateModels.Save body) {
        var entity = owned(tenant, user, id);
        validateSpec(entity, body.spec());
        if (!Set.of("self", "all").contains(body.readScope())) throw StructuredContent.error("模板可见范围无效");
        String name = validName(body.name());
        if ("published".equals(entity.status)) {
            // 没有内容变更时保存/试填不产生空版本，只有真正修改才建立后续草稿。
            com.fasterxml.jackson.databind.JsonNode nextSpec = mapper.valueToTree(body.spec());
            com.fasterxml.jackson.databind.JsonNode currentSpec = mapper.valueToTree(spec(entity));
            // 浏览器可能将 11.0 序列化成 11，数值等价不应造成模板多出一个版本。
            boolean sameSpec = currentSpec.equals((left, right) -> left.isNumber() && right.isNumber()
                ? left.decimalValue().compareTo(right.decimalValue()) : left.equals(right) ? 0 : 1, nextSpec);
            if (entity.name.equals(name) && entity.readScope.equals(body.readScope()) && sameSpec) return row(entity);
            entity = revision(entity, user);
        }
        entity.name = name; entity.readScope = body.readScope();
        entity.spec = mapper.convertValue(body.spec(), new TypeReference<>() {}); entity.updatedAt = clock.instant();
        repository.save(entity); event("保存模板草稿", entity, user); return row(entity);
    }
    @Transactional
    public TemplateModels.Row publish(UUID tenant, UUID user, UUID id) {
        var entity = owned(tenant, user, id);
        if ("published".equals(entity.status)) return row(entity);
        validateSpec(entity, spec(entity));
        // 发布时先以最小有效内容编译一次，尽早发现行内多段、跨行合并等不支持的结构。
        Map<String, Object> sample = new LinkedHashMap<>();
        for (var field : spec(entity).fields()) sample.put(field.key(), sample(field));
        renderer.render(entity.fileBytes, entity.kind, spec(entity), sample, Map.of());
        entity.status = "published"; entity.updatedAt = clock.instant(); repository.save(entity);
        event("发布模板版本", entity, user); return row(entity);
    }
    @Transactional
    public TemplateModels.Row copy(UUID tenant, UUID user, UUID id) {
        var source = read(tenant, user, id, false);
        // 复制始终创建独立模板；原模板的后续修改通过保存自动形成版本。
        var entity = new DeliveryTemplateEntity();
        entity.id = UUID.randomUUID(); entity.templateId = entity.id; entity.tenantId = tenant;
        entity.name = validName(source.name.length() > 150 ? source.name.substring(0, 150) + " 副本" : source.name + " 副本");
        entity.fileName = source.fileName; entity.kind = source.kind; entity.version = 1; entity.status = "draft";
        entity.readScope = "self"; entity.createdBy = user; entity.updatedAt = clock.instant();
        entity.fileBytes = source.fileBytes.clone(); entity.spec = new LinkedHashMap<>(source.spec);
        repository.save(entity); event("复制模板为草稿", entity, user); return row(entity);
    }
    /** 修改发布版时创建新草稿，旧版本及其流程绑定始终保持不可变。 */
    private DeliveryTemplateEntity revision(DeliveryTemplateEntity source, UUID user) {
        var root = repository.lockVersion(source.templateId, source.tenantId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "DELIVERY_TEMPLATE_NOT_FOUND", "模板不存在或不可访问"));
        if (root.deletedAt != null) throw StructuredContent.error("模板已删除，请复制为独立模板后编辑");
        if (repository.findFirstByTemplateIdAndStatusAndDeletedAtIsNullOrderByVersionDesc(source.templateId, "draft").isPresent())
            throw StructuredContent.error("该模板已有未发布草稿，请从模板库打开草稿继续编辑");
        var entity = new DeliveryTemplateEntity();
        entity.id = UUID.randomUUID(); entity.templateId = source.templateId; entity.tenantId = source.tenantId;
        entity.name = source.name; entity.fileName = source.fileName; entity.kind = source.kind;
        entity.version = repository.findFirstByTemplateIdOrderByVersionDesc(source.templateId).map(it -> it.version + 1).orElse(source.version + 1);
        entity.status = "draft"; entity.readScope = source.readScope; entity.createdBy = user; entity.updatedAt = clock.instant();
        entity.fileBytes = source.fileBytes.clone(); entity.spec = new LinkedHashMap<>(source.spec);
        event("开始模板新版本草稿", entity, user);
        return entity;
    }
    @Transactional(readOnly = true)
    public DocumentDeliveryFile original(UUID tenant, UUID user, UUID id) {
        var entity = read(tenant, user, id, false);
        return new DocumentDeliveryFile(entity.fileName, contentType(entity.kind), entity.fileBytes);
    }
    @Transactional
    public TemplateModels.Row replaceFile(UUID tenant, UUID user, UUID id, String name, byte[] bytes) {
        var entity = owned(tenant, user, id);
        if (bytes.length == 0 || bytes.length > 20 * 1024 * 1024 || !name.toLowerCase(Locale.ROOT).endsWith("word".equals(entity.kind) ? ".docx" : ".xlsx")) throw StructuredContent.error("请上传同类型、20 MB 以内的模板文件");
        var fresh = renderer.inspect(bytes, entity.kind);
        if ("published".equals(entity.status)) entity = revision(entity, user);
        entity.spec = mapper.convertValue(fresh, new TypeReference<>() {}); entity.fileBytes = bytes;
        entity.fileName = safeName(name, entity.kind); entity.updatedAt = clock.instant(); repository.save(entity);
        event("替换模板草稿原件并重新识别字段", entity, user); return row(entity);
    }
    @Transactional(readOnly = true)
    public DocumentDeliveryFile preview(UUID tenant, UUID user, UUID id, TemplateModels.Preview body) {
        var entity = read(tenant, user, id, false);
        var capability = capability(tenant, user, body.capabilityId(), entity.kind);
        var styles = body.locationStyles() == null ? Map.<String, Map<String, Object>>of() : body.locationStyles();
        validateOverrides(spec(entity), styles);
        byte[] bytes = renderer.render(entity.fileBytes, entity.kind, spec(entity), body.values() == null ? Map.of() : body.values(), styles);
        enforceSize(capability, bytes.length);
        event("生成模板试填文件", entity, user);
        return new DocumentDeliveryFile(safeName(body.fileName(), entity.kind), contentType(entity.kind), bytes);
    }
    @Transactional(readOnly = true)
    public void validateReference(UUID tenant, UUID user, Map<String, Object> config) {
        var entity = read(tenant, user, uuid(config.get("templateVersionId")), true);
        String kind = Objects.toString(config.get("documentKind"), "word_document".equals(config.get("deliveryType")) ? "word" : "excel");
        if (!entity.kind.equals(kind)) throw StructuredContent.error("模板文件类型与交付类型不一致");
        var bindings = bindings(config);
        Set<String> fields = new HashSet<>();
        for (var binding : bindings) {
            if (!fields.add(binding.field()) || spec(entity).fields().stream().noneMatch(it -> it.key().equals(binding.field()))) throw StructuredContent.error("模板绑定字段重复或不存在");
            if (!binding.constant()) for (var source : sources(binding)) {
                if (source.variable() == null || !source.variable().matches("[a-z][a-zA-Z0-9_]*") || source.sourceNodeId() == null || source.sourceNodeId().isBlank()) throw StructuredContent.error("请选择字段来源节点和变量");
                validatePath(source.path());
                if ("system".equals(source.sourceNodeId()) && !com.agentum.workflow.application.WorkflowRuntimeSystemVariables.descriptions().containsKey(source.variable())) throw StructuredContent.error("系统来源只能引用平台运行变量");
            }
            if (binding.columnMappings() != null) binding.columnMappings().values().forEach(DeliveryTemplateService::validatePath);
        }
        for (var field : spec(entity).fields()) if (field.required() && !fields.contains(field.key())) throw StructuredContent.error("必填字段尚未绑定：" + field.label());
        validateOverrides(spec(entity), overrides(config));
    }
    @Transactional(readOnly = true)
    public Map<String, Object> generate(DeliveryRuntimeRequest request, UUID recordId, SystemCapabilityEntity capability,
        Map<String, Object> config, Map<String, Object> variables) {
        validateReference(request.run().getTenantId(), request.operatorUserId(), config);
        var entity = read(request.run().getTenantId(), request.operatorUserId(), uuid(config.get("templateVersionId")), true);
        capability(request.run().getTenantId(), request.operatorUserId(), capability.getId(), entity.kind);
        Map<String, Object> values = new LinkedHashMap<>();
        var upstream = nodes.findByRunIdOrderBySortOrderAsc(request.run().getId());
        for (var binding : bindings(config)) {
            Object value = binding.value();
            boolean datasetSource = false;
            if (!binding.constant()) {
                Map<String, Object> source = variables;
                if (binding.sourceNodeId() != null && !binding.sourceNodeId().isBlank() && !"system".equals(binding.sourceNodeId())) {
                    var node = upstream.stream().filter(it -> binding.sourceNodeId().equals(it.getNodeKey())).findFirst().orElseThrow(() -> StructuredContent.error("来源节点不存在：" + binding.field()));
                    if (node.getSortOrder() >= request.nodeRun().getSortOrder() || !"completed".equals(node.getState())) throw StructuredContent.error("来源步骤尚未完成：" + binding.field());
                    source = node.getOutputSnapshot();
                }
                value = path(source.get(binding.variable()), binding.path());
                if ("mcp_datasets".equals(binding.variable()) && value == null) throw StructuredContent.error("本次执行没有产生所绑定的工具结果，请确认取数智能体及对应工具已执行：" + binding.field());
                if (value instanceof Map<?, ?> reference && reference.containsKey("datasetId")) {
                    var sourceNode = upstream.stream().filter(it -> binding.sourceNodeId().equals(it.getNodeKey())).findFirst().orElseThrow(() -> StructuredContent.error("数据集必须绑定明确的上游节点"));
                    datasetSource = true;
                    value = datasets.readRows(request.run().getTenantId(), request.run().getId(), sourceNode.getId(), request.operatorUserId(), value);
                }

            }
            var destination = spec(entity).fields().stream().filter(it -> it.key().equals(binding.field())).findFirst()
                .orElseThrow(() -> StructuredContent.error("绑定的模板字段不存在：" + binding.field()));
            if (!binding.constant() && "table".equals(destination.type()) && value instanceof List<?> list) {
                List<Map<String, Object>> projected = new ArrayList<>();
                for (Object raw : list) {
                    if (!(raw instanceof Map<?, ?>)) throw StructuredContent.error("明细来源中的每一项必须是对象");
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (var column : destination.columns()) {
                        Object cell = path(raw, binding.columnMappings() == null ? column.key() : binding.columnMappings().getOrDefault(column.key(), column.key()));
                        row.put(column.key(), datasetSource ? datasetCell(cell, column) : cell);
                    }
                    projected.add(row);
                }
                value = projected;
            }
            values.put(binding.field(), value);
        }
        validateOverrides(spec(entity), overrides(config));
        byte[] bytes = renderer.render(entity.fileBytes, entity.kind, spec(entity), values, overrides(config));
        enforceSize(capability, bytes.length);
        Map<String, Object> nameVariables = new LinkedHashMap<>(variables);
        nameVariables.put("runNumber", request.run().getRunNumber());
        String fileName = renderFileName(Objects.toString(config.get("fileNameTemplate"), "模板交付-{{runNumber}}"), nameVariables);
        var artifact = storage.store(request.run().getTenantId(), recordId, safeName(fileName, entity.kind), contentType(entity.kind), bytes);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("adapter", "word".equals(entity.kind) ? "word_document" : "excel_workbook");
        result.put("documentKind", entity.kind); result.put("fileName", artifact.fileName());
        result.put("contentType", contentType(entity.kind)); result.put("sizeBytes", artifact.sizeBytes());
        result.put("storageProvider", "minio"); result.put("storageKey", artifact.storageKey());
        result.put("templateVersionId", entity.id.toString()); result.put("templateName", entity.name);
        result.put("filledFieldCount", values.size());
        DeliveryFileRetention.from(capability.getConfig(), clock.instant()).writeTo(result);
        result.put("downloadUrl", "/api/tenants/" + request.run().getTenantId() + "/delivery-records/" + recordId + "/download");
        event("模板交付生成成功", entity, request.operatorUserId());
        return result;
    }
    // 中台常以十进制字符串返回金额，按模板显式类型转换；空值保持缺失，避免被误填成零。
    private Object datasetCell(Object value, TemplateModels.Field field) {
        if (value == null || value instanceof String text && text.isBlank()) return null;
        try {
            if ("number".equals(field.type())) return new java.math.BigDecimal(value.toString());
            // 同一中台日期列可能混用 20260513 与 2026-05-13；仅在填写日期字段时统一，原数据集不改写。
            if ("date".equals(field.type()) && (value instanceof String || value instanceof Number)) {
                String text = value.toString().strip();
                try {
                    return java.time.LocalDate.parse(text, text.matches("[0-9]{8}")
                        ? java.time.format.DateTimeFormatter.BASIC_ISO_DATE : java.time.format.DateTimeFormatter.ISO_LOCAL_DATE).toString();
                } catch (java.time.format.DateTimeParseException ex) {
                    throw StructuredContent.error("数据集日期字段必须为有效的 yyyy-MM-dd 或 yyyyMMdd 日期：" + field.label());
                }
            }
            if ("text".equals(field.type()) && (value instanceof String || value instanceof Number)) return value.toString();
            return value;
        } catch (NumberFormatException ex) { throw StructuredContent.error("数据集金额字段不能转换为数字：" + field.label()); }
    }
    public static Object path(Object value, String path) {
        validatePath(path);
        if (path == null || path.isBlank()) return value;
        for (String part : path.split("\\.")) {
            if (!(value instanceof Map<?, ?> map)) return null;
            value = map.get(part);
        }
        return value;
    }
    private static void validatePath(String path) {
        if (path != null && !path.isBlank() && !path.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")) throw StructuredContent.error("字段路径只能使用已声明的对象字段");
    }
    public List<TemplateModels.Binding> bindings(Map<String, Object> config) { return mapper.convertValue(config.getOrDefault("templateBindings", List.of()), new TypeReference<>() {}); }
    public List<TemplateModels.Source> sources(TemplateModels.Binding binding) {
        return List.of(new TemplateModels.Source(binding.sourceNodeId(), binding.variable(), binding.path()));
    }
    private Map<String, Map<String, Object>> overrides(Map<String, Object> config) { return mapper.convertValue(config.getOrDefault("templateLocationStyles", Map.of()), new TypeReference<>() {}); }
    private DeliveryTemplateEntity read(UUID tenant, UUID user, UUID id, boolean published) {
        var entity = repository.findByIdAndTenantId(id, tenant).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "DELIVERY_TEMPLATE_NOT_FOUND", "模板不存在或不可访问"));
        // 草稿仅创建者可读；共享只开放发布版本，禁止通过流程跨租户或借用他人私有模板。
        if (!entity.createdBy.equals(user) && !("published".equals(entity.status) && "all".equals(entity.readScope))) {
            event("模板资源访问被拒绝", entity, user);
            throw new ApiException(HttpStatus.FORBIDDEN, "DELIVERY_TEMPLATE_ACCESS_DENIED", "没有该模板的访问权限");
        }
        if (published && !"published".equals(entity.status)) throw StructuredContent.error("流程只能使用已发布模板");
        return entity;
    }
    private DeliveryTemplateEntity owned(UUID tenant, UUID user, UUID id) {
        var entity = read(tenant, user, id, false);
        if (!entity.createdBy.equals(user)) throw new ApiException(HttpStatus.FORBIDDEN, "DELIVERY_TEMPLATE_EDIT_DENIED", "只有创建者可以编辑或发布模板");
        if (entity.deletedAt != null) throw StructuredContent.error("模板已从库中删除，不能继续编辑或发布");
        return entity;
    }
    private TemplateModels.Spec spec(DeliveryTemplateEntity entity) {
        var spec = mapper.convertValue(entity.spec, TemplateModels.Spec.class);
        // 已上传草稿也按原件位置自动识别独立明细，无需重新上传或先经历一次发布失败。
        return "excel".equals(entity.kind) ? renderer.resolveExcelRegions(spec) : spec;
    }
    private Object sample(TemplateModels.Field field) {
        if (field.enumValues() != null && !field.enumValues().isEmpty()) return field.enumValues().getFirst();
        return switch (field.type()) {
            case "number" -> 0;
            case "boolean" -> false;
            case "date" -> "2000-01-01";
            case "paragraphs" -> List.of("例");
            case "table" -> {
                Map<String, Object> row = new LinkedHashMap<>(); for (var column : field.columns()) row.put(column.key(), sample(column)); yield List.of(row);
            }
            default -> "例";
        };
    }
    private TemplateModels.Row row(DeliveryTemplateEntity e) { return new TemplateModels.Row(e.id, e.templateId, e.name, e.fileName, e.kind, e.version, e.status, e.readScope, e.createdBy, e.updatedAt, e.deletedAt != null, spec(e)); }
    private void validateSpec(DeliveryTemplateEntity entity, TemplateModels.Spec spec) {
        if (spec == null || spec.locations() == null) throw StructuredContent.error("模板字段配置不能为空");
        StructuredContent.validateFields(spec.fields());
        if (!"excel".equals(entity.kind) && !spec.excelRegions().isEmpty()) throw StructuredContent.error("只有 Excel 支持独立数据区域");
        if (spec.excelRegions().size() > 3000) throw StructuredContent.error("明细填写位置不能超过 3000 个");
        for (var region : spec.excelRegions()) {
            if (!Set.of("shift", "fixed").contains(region.growth())) throw StructuredContent.error("明细扩展空间设置无效");
            if (!Set.of("vertical", "horizontal").contains(region.direction())) throw StructuredContent.error("区域扩展方向必须为纵向或横向");
            if (region.sheet() == null || region.startCell() == null || !region.startCell().matches("[A-Z]{1,3}[1-9][0-9]{0,5}") || spec.fields().stream().noneMatch(f -> f.key().equals(region.field()) && "table".equals(f.type()))) throw StructuredContent.error("独立区域的 Sheet、明细字段或开始位置无效");
            if (spec.locations().stream().noneMatch(l -> region.sheet().equals(l.sheet()) && region.field().equals(l.field()))) throw StructuredContent.error("区域的 Sheet 与模板明细位置不匹配");
        }
        var original = renderer.inspect(entity.fileBytes, entity.kind);
        if (!original.fields().stream().map(TemplateModels.Field::key).collect(java.util.stream.Collectors.toSet()).equals(spec.fields().stream().map(TemplateModels.Field::key).collect(java.util.stream.Collectors.toSet()))) throw StructuredContent.error("字段标识必须与文件中的填写标记一致");
        if (!original.locations().stream().map(TemplateModels.Location::id).toList().equals(spec.locations().stream().map(TemplateModels.Location::id).toList())) throw StructuredContent.error("填写位置不能脱离模板原件修改");
        for (int i = 0; i < original.locations().size(); i++) {
            var a = original.locations().get(i); var b = spec.locations().get(i);
            if (!a.field().equals(b.field()) || !a.sheet().equals(b.sheet())) throw StructuredContent.error("填写位置与模板原件不匹配");
        }
        for (var old : original.fields()) {
            var current = spec.fields().stream().filter(it -> it.key().equals(old.key())).findFirst()
                .orElseThrow(() -> StructuredContent.error("字段定义缺失：" + old.key()));
            if ("table".equals(old.type()) != "table".equals(current.type())) throw StructuredContent.error("重复表格字段不能修改为普通字段");
            if ("table".equals(old.type()) && !old.columns().stream().map(TemplateModels.Field::key).toList().equals(current.columns().stream().map(TemplateModels.Field::key).toList())) throw StructuredContent.error("表格列必须与样板行标记一致");
        }
        validateStyle(spec.defaultStyle());
        if (spec.sheetStyles() != null) spec.sheetStyles().values().forEach(this::validateStyle);
        spec.locations().forEach(it -> validateStyle(it.style()));
    }
    private void validateOverrides(TemplateModels.Spec spec, Map<String, Map<String, Object>> styles) {
        for (var entry : styles.entrySet()) {
            if (spec.locations().stream().noneMatch(it -> it.id().equals(entry.getKey()))) throw StructuredContent.error("样式覆盖位置不在模板中");
            validateStyle(entry.getValue());
        }
    }
    public void validateStyle(Map<String, Object> style) {
        if (style == null) return;
        if ("fixed".equals(style.get("rowHeightMode")) && !style.containsKey("rowHeight")) throw StructuredContent.error("固定行高需要填写磅值");
        Set<String> numeric = Set.of("fontSize", "firstLineChars", "leftIndentCm", "spacingBefore", "spacingAfter", "lineSpacing", "rowHeight", "columnWidth", "cellPaddingPt", "indent");
        Set<String> flags = Set.of("bold", "italic", "wrapText", "border");
        for (var entry : style.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
            if (numeric.contains(key)) {
                if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() < 0 || n.doubleValue() > ("columnWidth".equals(key) ? 255 : 400)) throw StructuredContent.error("样式数值超出范围：" + key);
                if (Set.of("fontSize", "lineSpacing", "rowHeight").contains(key) && ((Number) value).doubleValue() == 0) throw StructuredContent.error("字号、行距和行高必须大于 0");
                if ("indent".equals(key) && ((Number) value).doubleValue() > 250) throw StructuredContent.error("单元格缩进不能超过 250");
            } else if (flags.contains(key)) { if (!(value instanceof Boolean)) throw StructuredContent.error("样式开关无效：" + key); }
            else if (Set.of("color", "backgroundColor").contains(key)) { if (!(value instanceof String s) || !s.matches("#?[0-9A-Fa-f]{6}")) throw StructuredContent.error("颜色应为六位十六进制值"); }
            else if ("verticalAlignment".equals(key)) { if (!Set.of("top", "center", "bottom").contains(value)) throw StructuredContent.error("垂直对齐方式无效"); }
            else if ("rowHeightMode".equals(key)) { if (!Set.of("auto", "inherit", "fixed").contains(value)) throw StructuredContent.error("行高方式无效"); }
            else if ("alignment".equals(key)) { if (!Set.of("left", "center", "right", "both").contains(value)) throw StructuredContent.error("对齐方式无效"); }
            else if (!Set.of("fontFamily", "numberFormat").contains(key) || !(value instanceof String s) || s.length() > 100) throw StructuredContent.error("不支持的样式属性：" + key);
        }
    }
    private SystemCapabilityEntity capability(UUID tenant, UUID user, UUID id, String kind) {
        var capability = capabilities.findById(id).orElseThrow(() -> StructuredContent.error("交付能力不存在"));
        String configuredKind = Objects.toString(capability.getConfig().get("documentKind"), "word");
        if (!"active".equals(capability.getStatus()) || !"delivery".equals(capability.getCapabilityType()) || !kind.equals(configuredKind) || !assets.canUseSystemCapabilityReference(tenant, user, id, "delivery")) throw new ApiException(HttpStatus.FORBIDDEN, "DELIVERY_TEMPLATE_CAPABILITY_DENIED", "当前账号没有对应的文件交付能力");
        return capability;
    }
    private void enforceSize(SystemCapabilityEntity capability, int size) {
        int limit = capability.getConfig().get("maxFileSizeMb") instanceof Number n ? n.intValue() : 20;
        if (size > (long) Math.max(1, Math.min(200, limit)) * 1024 * 1024) throw StructuredContent.error("生成文件超过交付能力的大小限制");
    }
    private static UUID uuid(Object value) { try { return UUID.fromString(Objects.toString(value, "")); } catch (Exception ex) { throw StructuredContent.error("请选择有效的模板版本"); } }
    private String validName(String name) { if (name == null || name.isBlank() || name.strip().length() > 160) throw StructuredContent.error("模板名称应为 1～160 个字符"); return name.strip(); }
    /** 文件名按单次替换处理，数据中的花括号不会被再次解释；缺值不能悄悄输出未解析的占位符。 */
    static String renderFileName(String template, Map<String, Object> variables) {
        var pattern = java.util.regex.Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)\\s*}}");
        var matcher = pattern.matcher(template);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            Object value = path(variables, key);
            if (!(value instanceof String || value instanceof Number || value instanceof Boolean) || value.toString().isBlank())
                throw StructuredContent.error("文件名变量缺失或不是文字、数字、日期：" + key);
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(value.toString()));
        }
        matcher.appendTail(result);
        return result.toString();
    }
    private String safeName(String value, String kind) { return DocumentDeliveryStorage.sanitizeFileName(value, "模板交付" + ("word".equals(kind) ? ".docx" : ".xlsx"), "word".equals(kind) ? ".docx" : ".xlsx"); }
    private String contentType(String kind) { return "word".equals(kind) ? MarkdownDocxRenderer.DOCX_CONTENT_TYPE : ExcelWorkbookRenderer.XLSX_CONTENT_TYPE; }
    private void event(String action, DeliveryTemplateEntity e, UUID user) { log.info("{} tenantId={} userId={} templateVersionId={} requestId={}", action, e.tenantId, user, e.id, RequestIds.current()); }
}
