package com.agentum.delivery.template;

import com.agentum.asset.application.AssetAccess;
import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.delivery.application.DocumentDeliveryFile;
import com.agentum.shared.api.*;
import com.agentum.shared.pagination.PageResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.Objects;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** 文件字节始终经过租户和模板资源授权，不暴露对象存储地址。 */
@RestController
@RequestMapping("/api/tenants/{tenantId}/delivery-templates")
public class DeliveryTemplateController {
    private final AssetAccess access;
    private final DeliveryTemplateService service;
    public DeliveryTemplateController(AssetAccess access, DeliveryTemplateService service) { this.access = access; this.service = service; }
    @GetMapping
    public ApiResponse<PageResponse<TemplateModels.Row>> list(@PathVariable UUID tenantId, @AuthenticationPrincipal CurrentUserPrincipal user,
        @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int size,
        @RequestParam(defaultValue = "false") boolean published, @RequestParam(defaultValue = "") String keyword) {
        access.assertCanUseAssets(user, tenantId);
        return ApiResponse.success(service.list(tenantId, user.userId(), page, size, published, keyword), RequestIds.current());
    }
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<TemplateModels.Row> upload(@PathVariable UUID tenantId, @AuthenticationPrincipal CurrentUserPrincipal user,
        @RequestParam String name, @RequestPart MultipartFile file) throws java.io.IOException {
        access.assertCanUseAssets(user, tenantId);
        if (file.getSize() > 20 * 1024 * 1024) throw StructuredContent.error("模板最大为 20 MB");
        return ApiResponse.success(service.upload(tenantId, user.userId(), name, file.getOriginalFilename() == null ? "" : file.getOriginalFilename(), file.getBytes()), RequestIds.current());
    }
    @GetMapping("/{id}")
    public ApiResponse<TemplateModels.Row> get(@PathVariable UUID tenantId, @PathVariable UUID id, @AuthenticationPrincipal CurrentUserPrincipal user) {
        access.assertCanUseAssets(user, tenantId); return ApiResponse.success(service.get(tenantId, user.userId(), id), RequestIds.current());
    }
    @PutMapping("/{id}")
    public ApiResponse<TemplateModels.Row> save(@PathVariable UUID tenantId, @PathVariable UUID id, @AuthenticationPrincipal CurrentUserPrincipal user, @RequestBody TemplateModels.Save body) {
        access.assertCanUseAssets(user, tenantId); return ApiResponse.success(service.save(tenantId, user.userId(), id, body), RequestIds.current());
    }
    @PostMapping("/{id}/publish")
    public ApiResponse<TemplateModels.Row> publish(@PathVariable UUID tenantId, @PathVariable UUID id, @AuthenticationPrincipal CurrentUserPrincipal user) {
        access.assertCanUseAssets(user, tenantId); return ApiResponse.success(service.publish(tenantId, user.userId(), id), RequestIds.current());
    }
    @PostMapping("/{id}/copy")
    public ApiResponse<TemplateModels.Row> copy(@PathVariable UUID tenantId, @PathVariable UUID id, @AuthenticationPrincipal CurrentUserPrincipal user) {
        access.assertCanUseAssets(user, tenantId); return ApiResponse.success(service.copy(tenantId, user.userId(), id), RequestIds.current());
    }
    @GetMapping("/{id}/file")
    public ResponseEntity<ByteArrayResource> file(@PathVariable UUID tenantId, @PathVariable UUID id, @AuthenticationPrincipal CurrentUserPrincipal user) {
        access.assertCanUseAssets(user, tenantId); return response(service.original(tenantId, user.userId(), id));
    }
    @PostMapping(value = "/{id}/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<TemplateModels.Row> replaceFile(@PathVariable UUID tenantId, @PathVariable UUID id, @AuthenticationPrincipal CurrentUserPrincipal user, @RequestPart MultipartFile file) throws java.io.IOException {
        access.assertCanUseAssets(user, tenantId);
        if (file.getSize() > 20 * 1024 * 1024) throw StructuredContent.error("模板最大为 20 MB");
        return ApiResponse.success(service.replaceFile(tenantId, user.userId(), id, Objects.toString(file.getOriginalFilename(), ""), file.getBytes()), RequestIds.current());
    }
    @PostMapping("/{id}/preview")
    public ResponseEntity<ByteArrayResource> preview(@PathVariable UUID tenantId, @PathVariable UUID id, @AuthenticationPrincipal CurrentUserPrincipal user, @RequestBody TemplateModels.Preview body) {
        access.assertCanUseAssets(user, tenantId); return response(service.preview(tenantId, user.userId(), id, body));
    }
    @PostMapping("/{id}/preview-layout")
    public ApiResponse<java.util.List<TemplateWorkbookPreview.Sheet>> previewLayout(@PathVariable UUID tenantId, @PathVariable UUID id, @AuthenticationPrincipal CurrentUserPrincipal user, @RequestBody TemplateModels.Preview body) {
        access.assertCanUseAssets(user, tenantId);
        var file = service.preview(tenantId, user.userId(), id, body);
        if (!file.fileName().endsWith(".xlsx")) throw StructuredContent.error("样式表格预览仅适用于 Excel");
        return ApiResponse.success(TemplateWorkbookPreview.read(file.bytes()), RequestIds.current());
    }
    private ResponseEntity<ByteArrayResource> response(DocumentDeliveryFile file) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.contentType()))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
            .header("X-Request-Id", RequestIds.current()).contentLength(file.bytes().length).body(new ByteArrayResource(file.bytes()));
    }
}
