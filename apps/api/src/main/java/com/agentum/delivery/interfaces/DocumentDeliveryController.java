package com.agentum.delivery.interfaces;

import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.delivery.application.DocumentDeliveryFile;
import com.agentum.delivery.application.DocumentDeliveryPreviewCommand;
import com.agentum.delivery.application.DocumentDeliveryService;
import com.agentum.shared.api.RequestIds;
import com.agentum.workflow.application.WorkflowDesignAccess;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tenants/{tenantId}/document-deliveries")
public class DocumentDeliveryController {

    private final WorkflowDesignAccess workflowDesignAccess;
    private final DocumentDeliveryService documentDeliveryService;

    public DocumentDeliveryController(
        WorkflowDesignAccess workflowDesignAccess,
        DocumentDeliveryService documentDeliveryService
    ) {
        this.workflowDesignAccess = workflowDesignAccess;
        this.documentDeliveryService = documentDeliveryService;
    }

    @PostMapping("/preview")
    public ResponseEntity<ByteArrayResource> preview(
        @PathVariable UUID tenantId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        @RequestBody DocumentDeliveryPreviewRequest body,
        HttpServletRequest request
    ) {
        workflowDesignAccess.assertCanDesign(principal, tenantId);
        DocumentDeliveryFile file = documentDeliveryService.preview(
            tenantId,
            principal.userId(),
            new DocumentDeliveryPreviewCommand(body.capabilityId(), body.markdown(), body.fileName(), body.title(), body.style())
        );
        return fileResponse(file, RequestIds.current(request));
    }

    private ResponseEntity<ByteArrayResource> fileResponse(DocumentDeliveryFile file, String requestId) {
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(file.contentType()))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(file.fileName(), StandardCharsets.UTF_8)
                .build()
                .toString())
            .header("X-Request-Id", requestId)
            .contentLength(file.bytes().length)
            .body(new ByteArrayResource(file.bytes()));
    }

    public record DocumentDeliveryPreviewRequest(
        String capabilityId,
        String markdown,
        String fileName,
        String title,
        Map<String, Object> style
    ) {
    }
}
