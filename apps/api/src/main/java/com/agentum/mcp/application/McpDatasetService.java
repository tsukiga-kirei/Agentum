package com.agentum.mcp.application;

import com.agentum.attachment.application.AttachmentObjectStorage;
import com.agentum.mcp.domain.McpDatasetEntity;
import com.agentum.mcp.infrastructure.McpDatasetRepository;
import com.agentum.shared.api.RequestIds;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.slf4j.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 数据集仅供本次运行使用；所有模型视图与交付读取都重新校验运行和租户，不将 ID 当作凭证。 */
@Service
public class McpDatasetService {
    private static final Logger log = LoggerFactory.getLogger(McpDatasetService.class);
    private final McpDatasetRepository repository;
    private final AttachmentObjectStorage storage;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final com.agentum.asset.application.AssetManagementService assets;
    public McpDatasetService(McpDatasetRepository repository, AttachmentObjectStorage storage, ObjectMapper mapper, Clock clock, com.agentum.asset.application.AssetManagementService assets) {
        this.repository = repository; this.storage = storage; this.mapper = mapper; this.clock = clock; this.assets = assets;
    }
    public Map<String, Object> save(McpRuntimeRequest request, UUID capabilityId, UUID callId, String key,
                                    List<Map<String, Object>> rows, Map<String, Object> policy) {
        byte[] bytes = encode(rows);
        if (bytes.length > 20 * 1024 * 1024) throw McpResultPolicy.error("数据集超过 20 MB，请缩小查询范围");
        var entity = new McpDatasetEntity();
        entity.id = UUID.randomUUID(); entity.tenantId = request.run().getTenantId(); entity.runId = request.run().getId();
        entity.nodeRunId = request.nodeRun().getId(); entity.capabilityId = capabilityId; entity.callLogId = callId;
        entity.datasetKey = key; entity.rowCount = rows.size(); entity.policy = new LinkedHashMap<>(policy);
        entity.createdAt = clock.instant();
        entity.storageKey = storage.storeOriginal(entity.tenantId, entity.id, "mcp-dataset.json", "application/json", bytes);
        try { repository.save(entity); }
        catch (RuntimeException ex) { storage.delete(entity.storageKey); throw ex; }
        log.info("MCP 数据集已保存 tenantId={} userId={} runId={} nodeRunId={} datasetId={} rowCount={} requestId={}",
            entity.tenantId, request.operatorUserId(), entity.runId, entity.nodeRunId, entity.id, rows.size(), RequestIds.current());
        return view(entity, rows);
    }
    public Map<String, Object> references(UUID nodeRunId, Set<String> callIds) {
        Map<String, Object> result = new LinkedHashMap<>();
        // 同一节点显式重复取数时最后完成的结果为当前绑定，交付引用固定该 UUID，不混合历史分页。
        for (var entity : repository.findByNodeRunIdOrderByCreatedAtAsc(nodeRunId)) if (callIds.contains(entity.callLogId.toString())) {
            String capability = "cap_" + entity.capabilityId.toString().replace('-', '_');
            @SuppressWarnings("unchecked") var scoped = (Map<String, Object>) result.computeIfAbsent(capability, ignored -> new LinkedHashMap<String, Object>());
            scoped.put(entity.datasetKey, reference(entity));
        }
        return result;
    }
    public List<Map<String, Object>> readRows(UUID tenant, UUID run, UUID sourceNode, UUID userId, Object reference) {
        Map<String, Object> ref = McpResultPolicy.map(reference);
        var entity = require(tenant, run, Objects.toString(ref.get("datasetId"), ""));
        if (!entity.nodeRunId.equals(sourceNode)) throw McpResultPolicy.error("数据集与绑定的来源节点不匹配");
        authorize(entity, userId);
        return load(entity);
    }
    public byte[] download(UUID tenant, UUID run, UUID user, String id) {
        var entity = require(tenant, run, id); authorize(entity, user);
        log.info("下载流程 MCP 完整结果 tenantId={} userId={} runId={} datasetId={} requestId={}", tenant, user, run, entity.id, RequestIds.current());
        return encode(load(entity));
    }
    public Map<String, Object> query(McpRuntimeRequest request, Set<UUID> allowedCapabilities, Map<String, Object> args) {
        if (!Set.of("query", "describe").contains(Objects.toString(args.getOrDefault("action", "query")))) throw McpResultPolicy.error("数据集查询动作无效");
        var entity = require(request.run().getTenantId(), request.run().getId(), Objects.toString(args.get("datasetId"), ""));
        // 只能查询当前智能体已授权的 MCP 来源，不能借运行内引用扩大可用能力范围。
        if (!allowedCapabilities.contains(entity.capabilityId)) throw McpResultPolicy.error("当前智能体未授权访问该数据集来源");
        authorize(entity, request.operatorUserId());
        List<Map<String, Object>> rows = load(entity);
        if ("describe".equals(args.get("action"))) return view(entity, rows);
        List<String> allowed = fields(entity.policy, rows);
        List<String> selected = args.get("fields") instanceof List<?> list ? list.stream().map(Object::toString).toList() : allowed;
        if (!allowed.containsAll(selected)) throw McpResultPolicy.error("查询字段超出模型可见范围");
        Map<String, Object> filter = McpResultPolicy.map(args.get("equals"));
        if (!allowed.containsAll(filter.keySet())) throw McpResultPolicy.error("筛选字段超出模型可见范围");
        int limit = McpResultPolicy.integer(args, "limit", 20, 100);
        int offset;
        try { offset = Integer.parseInt(Objects.toString(args.getOrDefault("offset", 0))); }
        catch (NumberFormatException ex) { throw McpResultPolicy.error("offset 必须为非负整数"); }
        if (offset < 0 || offset > 100000) throw McpResultPolicy.error("offset 超出允许范围");
        var filtered = rows.stream().filter(row -> filter.entrySet().stream().allMatch(e -> Objects.equals(row.get(e.getKey()), e.getValue()))).toList();
        List<Map<String, Object>> page = new ArrayList<>();
        int cursor = Math.min(offset, filtered.size());
        while (cursor < filtered.size() && page.size() < limit) {
            Map<String, Object> item = project(filtered.get(cursor), selected);
            page.add(item);
            if (encode(page).length > 24000) { page.removeLast(); if (page.isEmpty()) throw McpResultPolicy.error("单条记录超过模型查询上限，请减少查询字段"); break; }
            cursor++;
        }
        log.info("MCP 数据集查询完成 tenantId={} userId={} runId={} datasetId={} returned={} requestId={}", entity.tenantId, request.operatorUserId(), entity.runId, entity.id, page.size(), RequestIds.current());
        return Map.of("datasetId", entity.id.toString(), "rows", page, "matchedCount", filtered.size(), "nextOffset", cursor, "hasMore", cursor < filtered.size());
    }
    private void authorize(McpDatasetEntity entity, UUID user) {
        if (!assets.canUseSystemCapabilityReference(entity.tenantId, user, entity.capabilityId, "mcp")) {
            log.warn("MCP 数据集访问被拒绝 tenantId={} userId={} datasetId={} requestId={}", entity.tenantId, user, entity.id, RequestIds.current());
            throw McpResultPolicy.error("数据集来源能力已收回或当前用户未获授权");
        }
    }
    private McpDatasetEntity require(UUID tenant, UUID run, String id) {
        UUID uuid;
        try { uuid = UUID.fromString(id); } catch (IllegalArgumentException ex) { throw McpResultPolicy.error("数据集引用不合法"); }
        var entity = repository.findByIdAndTenantIdAndRunId(uuid, tenant, run).orElseThrow(() -> McpResultPolicy.error("数据集不存在或不属于本次运行"));
        return entity;
    }
    private List<Map<String, Object>> load(McpDatasetEntity entity) {
        try { return mapper.readValue(storage.read(entity.storageKey), new TypeReference<>() {}); }
        catch (java.io.IOException ex) { throw McpResultPolicy.error("数据集内容无法读取"); }
    }
    private Map<String, Object> reference(McpDatasetEntity entity) {
        return Map.of("datasetId", entity.id.toString(), "datasetKey", entity.datasetKey, "rowCount", entity.rowCount, "runId", entity.runId.toString(),
            "complete", true);
    }
    private Map<String, Object> view(McpDatasetEntity entity, List<Map<String, Object>> rows) {
        Map<String, Object> result = new LinkedHashMap<>(reference(entity));
        List<String> fields = fields(entity.policy, rows);
        result.put("fields", fields); result.put("summary", "完整数据已保存；预览仅为原始顺序的部分记录，不代表整体分布。");
        String mode = Objects.toString(entity.policy.getOrDefault("preview", "summary"));
        int n = McpResultPolicy.integer(entity.policy, "previewRows", 3, 100);
        List<Map<String, Object>> preview = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) if ((Set.of("head", "head_tail").contains(mode) && i < n) || (Set.of("tail", "head_tail").contains(mode) && i >= rows.size() - n)) preview.add(project(rows.get(i), fields));
        // 即使一条字段值异常巨大，也不让预览突破消息上限；引用及条数仍然完整保留。
        while (!preview.isEmpty() && encode(preview).length > 24000) preview.removeLast();
        result.put("preview", preview); result.put("previewMode", mode); return result;
    }
    private List<String> fields(Map<String, Object> policy, List<Map<String, Object>> rows) {
        if (policy.get("fields") instanceof List<?> list) return list.stream().map(Object::toString).toList();
        Set<String> fields = new LinkedHashSet<>(); rows.forEach(row -> fields.addAll(row.keySet()));
        return new ArrayList<>(fields).stream().limit(100).toList();
    }
    private Map<String, Object> project(Map<String, Object> row, List<String> fields) {
        Map<String, Object> result = new LinkedHashMap<>(); fields.forEach(field -> { if (row.containsKey(field)) result.put(field, row.get(field)); }); return result;
    }
    private byte[] encode(Object value) {
        try { return mapper.writeValueAsBytes(value); } catch (java.io.IOException ex) { throw McpResultPolicy.error("数据集序列化失败"); }
    }
    // 完整结果与流程记录同生命周期；删除流程后清理对象，失败保留元信息以便下次重试。
    @Scheduled(fixedDelayString = "${agentum.mcp.dataset-cleanup-delay-ms:3600000}")
    public void cleanup() {
        for (var entity : repository.findOrphanedRunResults()) {
            try { storage.delete(entity.storageKey); repository.delete(entity);
                log.info("已清理删除流程的 MCP 结果 tenantId={} runId={} datasetId={} requestId={}", entity.tenantId, entity.runId, entity.id, RequestIds.current()); }
            catch (RuntimeException ex) { log.warn("已删除流程的 MCP 结果清理失败 tenantId={} datasetId={} requestId={}", entity.tenantId, entity.id, RequestIds.current()); }
        }
    }
}
