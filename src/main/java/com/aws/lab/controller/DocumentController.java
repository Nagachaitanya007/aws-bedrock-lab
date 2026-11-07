package com.aws.lab.controller;

import com.aws.lab.dto.DocumentStatusResponse;
import com.aws.lab.dto.DocumentUploadResponse;
import com.aws.lab.service.BackgroundPipelineWorker;
import com.aws.lab.service.DocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/v1/documents")
@CrossOrigin(origins = "*")
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);
    private final DocumentService documentService;
    private final BackgroundPipelineWorker backgroundPipelineWorker;

    public DocumentController(DocumentService documentService, BackgroundPipelineWorker backgroundPipelineWorker) {
        this.documentService = documentService;
        this.backgroundPipelineWorker = backgroundPipelineWorker;
    }

    /**
     * Universal Document Upload Endpoint.
     * Accepts any document (PDF, Markdown, TXT, JSON, DOCX, XLSX),
     * persists raw bytes to S3, and emits an SQS Claim-Check message.
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "documentType", defaultValue = "GENERAL") String documentType,
            @RequestParam(value = "classification", defaultValue = "INTERNAL") String classification,
            @RequestParam(value = "allowedRoles", required = false) String allowedRolesParam,
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "engineering-dept") String tenantId,
            @RequestHeader(value = "X-User-Roles", defaultValue = "ENGINEERING") String userRolesHeader
    ) {
        try {
            List<String> roles = allowedRolesParam != null && !allowedRolesParam.isBlank()
                    ? parseRoles(allowedRolesParam)
                    : parseRoles(userRolesHeader);

            DocumentUploadResponse response = documentService.uploadDocument(
                    file,
                    title,
                    documentType,
                    tenantId,
                    classification,
                    roles
            );

            log.info("[DocumentController] Accepted document upload: docId={}, format={}",
                    response.documentId(), response.sourceFormat());

            return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("BAD_REQUEST", e.getMessage()));
        } catch (IOException e) {
            log.error("[DocumentController] Storage upload error: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("STORAGE_ERROR", "Failed to upload document: " + e.getMessage()));
        }
    }

    /**
     * Document Lifecycle Status Polling Endpoint.
     * Returns whether the document is PROCESSING, ACTIVE, or FAILED.
     */
    @GetMapping("/{documentId}/status")
    public ResponseEntity<?> getDocumentStatus(@PathVariable String documentId) {
        DocumentStatusResponse status = documentService.getDocumentStatus(documentId);
        if (status == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("NOT_FOUND", "No document registered with ID: " + documentId));
        }
        return ResponseEntity.ok(status);
    }

    /**
     * Background Pipeline Worker Status Endpoint.
     */
    @GetMapping("/workers/status")
    public ResponseEntity<?> getWorkerStatus() {
        return ResponseEntity.ok(backgroundPipelineWorker.getWorkerStatus());
    }

    /**
     * Toggle Automatic Worker Mode (true = automatic, false = manual).
     */
    @PostMapping("/workers/toggle")
    public ResponseEntity<?> toggleWorker(@RequestParam boolean autoEnabled) {
        backgroundPipelineWorker.setAutoEnabled(autoEnabled);
        return ResponseEntity.ok(backgroundPipelineWorker.getWorkerStatus());
    }

    /**
     * Manually trigger an immediate queue drain (Universal Parser + Semantic Indexer).
     */
    @PostMapping("/workers/drain")
    public ResponseEntity<?> drainWorkers() {
        return ResponseEntity.ok(backgroundPipelineWorker.drainQueues("MANUAL_REST"));
    }

    private List<String> parseRoles(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of("ENGINEERING");
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public record ErrorResponse(String code, String message) {}
}
