package com.agentum.attachment.interfaces;

import com.agentum.attachment.application.AttachmentFileAccess;
import com.agentum.attachment.application.InputAttachmentService;
import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.shared.api.ApiResponse;
import com.agentum.shared.api.RequestIds;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/tenants/{tenantId}/workbench/runs/{runId}/nodes/{nodeRunId}/attachments")
public class InputAttachmentController {

    private final InputAttachmentService attachmentService;

    public InputAttachmentController(InputAttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<AttachmentApi.AttachmentRow> upload(
        @PathVariable UUID tenantId,
        @PathVariable UUID runId,
        @PathVariable UUID nodeRunId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @RequestPart("fieldId") String fieldId,
        @RequestPart("file") MultipartFile file,
        HttpServletRequest request
    ) {
        return ApiResponse.success(
            attachmentService.upload(tenantId, runId, nodeRunId, principal, fieldId, file),
            RequestIds.current(request)
        );
    }

    @GetMapping
    public ApiResponse<AttachmentApi.AttachmentList> list(
        @PathVariable UUID tenantId,
        @PathVariable UUID runId,
        @PathVariable UUID nodeRunId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @RequestParam String fieldId,
        HttpServletRequest request
    ) {
        return ApiResponse.success(
            attachmentService.list(tenantId, runId, nodeRunId, principal, fieldId),
            RequestIds.current(request)
        );
    }

    @GetMapping("/{attachmentId}/content")
    public ResponseEntity<ByteArrayResource> download(
        @PathVariable UUID tenantId,
        @PathVariable UUID runId,
        @PathVariable UUID nodeRunId,
        @PathVariable UUID attachmentId,
        @AuthenticationPrincipal CurrentUserPrincipal principal
    ) {
        AttachmentFileAccess file = attachmentService.download(tenantId, runId, nodeRunId, attachmentId, principal);
        return fileResponse(file, false);
    }

    @GetMapping("/{attachmentId}/parsed-preview")
    public ResponseEntity<ByteArrayResource> parsedPreview(
        @PathVariable UUID tenantId,
        @PathVariable UUID runId,
        @PathVariable UUID nodeRunId,
        @PathVariable UUID attachmentId,
        @AuthenticationPrincipal CurrentUserPrincipal principal
    ) {
        AttachmentFileAccess file = attachmentService.parsedPreview(tenantId, runId, nodeRunId, attachmentId, principal);
        return fileResponse(file, true);
    }

    @DeleteMapping("/{attachmentId}")
    public ApiResponse<Void> delete(
        @PathVariable UUID tenantId,
        @PathVariable UUID runId,
        @PathVariable UUID nodeRunId,
        @PathVariable UUID attachmentId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        HttpServletRequest request
    ) {
        attachmentService.delete(tenantId, runId, nodeRunId, attachmentId, principal);
        return ApiResponse.success(null, RequestIds.current(request));
    }

    private static ResponseEntity<ByteArrayResource> fileResponse(AttachmentFileAccess file, boolean inline) {
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(file.contentType());
        } catch (Exception exception) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        ContentDisposition disposition = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
            .filename(file.fileName(), StandardCharsets.UTF_8)
            .build();
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .header("X-Content-Type-Options", "nosniff")
            .contentType(mediaType)
            .contentLength(file.bytes().length)
            .body(new ByteArrayResource(file.bytes()));
    }
}
