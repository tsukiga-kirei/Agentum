package com.agentum.delivery.interfaces;

import com.agentum.auth.application.CurrentUserPrincipal;
import com.agentum.delivery.application.DocumentDeliveryFile;
import com.agentum.delivery.application.DocumentDeliveryService;
import com.agentum.delivery.domain.DeliveryRecordEntity;
import com.agentum.delivery.infrastructure.DeliveryRecordRepository;
import com.agentum.shared.api.ApiException;
import com.agentum.shared.api.RequestIds;
import com.agentum.workbench.application.WorkbenchAccess;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tenants/{tenantId}/delivery-records")
public class DeliveryRecordController {

    private final WorkbenchAccess workbenchAccess;
    private final DeliveryRecordRepository deliveryRecordRepository;
    private final DocumentDeliveryService documentDeliveryService;

    public DeliveryRecordController(
        WorkbenchAccess workbenchAccess,
        DeliveryRecordRepository deliveryRecordRepository,
        DocumentDeliveryService documentDeliveryService
    ) {
        this.workbenchAccess = workbenchAccess;
        this.deliveryRecordRepository = deliveryRecordRepository;
        this.documentDeliveryService = documentDeliveryService;
    }

    @GetMapping("/{recordId}/download")
    public ResponseEntity<ByteArrayResource> download(
        @PathVariable UUID tenantId,
        @PathVariable UUID recordId,
        @AuthenticationPrincipal CurrentUserPrincipal principal,
        HttpServletRequest request
    ) {
        workbenchAccess.assertCanAccessWorkbenchOrSchedule(principal, tenantId);
        DeliveryRecordEntity record = deliveryRecordRepository.findByIdAndTenantId(recordId, tenantId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "DELIVERY_RECORD_NOT_FOUND", "交付记录不存在"));
        DocumentDeliveryFile file = documentDeliveryService.readRecordFile(record);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(file.contentType()))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(file.fileName(), StandardCharsets.UTF_8)
                .build()
                .toString())
            .header("X-Request-Id", RequestIds.current(request))
            .contentLength(file.bytes().length)
            .body(new ByteArrayResource(file.bytes()));
    }
}
