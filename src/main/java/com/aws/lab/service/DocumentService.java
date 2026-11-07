package com.aws.lab.service;

import com.aws.lab.DbConfig;
import com.aws.lab.dto.DocumentStatusResponse;
import com.aws.lab.dto.DocumentUploadResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final S3Client s3Client;
    private final SqsClient sqsClient;
    private final ObjectMapper mapper;

    @Value("${doclab.s3.bucket-name:doclab-docs-c3fe55a3}")
    private String s3BucketName;

    @Value("${doclab.sqs.parsing-queue-url:https://sqs.us-east-1.amazonaws.com/564956048142/doclab-parsing-queue}")
    private String parsingQueueUrl;

    public DocumentService(S3Client s3Client, SqsClient sqsClient, ObjectMapper mapper) {
        this.s3Client = s3Client;
        this.sqsClient = sqsClient;
        this.mapper = mapper;
    }

    public DocumentUploadResponse uploadDocument(
            MultipartFile file,
            String documentTitle,
            String documentType,
            String tenantId,
            String classification,
            List<String> allowedRoles
    ) throws IOException {

        if (file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file cannot be empty");
        }

        String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "document";
        String format = extractFormat(originalFilename);
        String docId = "doc-" + UUID.randomUUID().toString().substring(0, 8);
        int version = 1;
        String title = (documentTitle != null && !documentTitle.isBlank()) ? documentTitle : originalFilename;
        String docType = (documentType != null && !documentType.isBlank()) ? documentType : "GENERAL";
        String tenant = (tenantId != null && !tenantId.isBlank()) ? tenantId : "engineering-dept";
        String classif = (classification != null && !classification.isBlank()) ? classification : "INTERNAL";
        List<String> roles = (allowedRoles != null && !allowedRoles.isEmpty()) ? allowedRoles : List.of("ENGINEERING");

        String s3RawKey = String.format("raw/%s-v%d-%s", docId, version, sanitizeFilename(originalFilename));

        log.info("[DocumentService] Uploading {} ({} bytes, format: {}) to s3://{}/{}",
                originalFilename, file.getSize(), format, s3BucketName, s3RawKey);

        // 1. Upload raw payload to S3
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(s3BucketName)
                        .key(s3RawKey)
                        .contentType(file.getContentType())
                        .build(),
                RequestBody.fromBytes(file.getBytes())
        );

        // 2. Build Claim-Check message payload for SQS
        ObjectNode payload = mapper.createObjectNode();
        payload.put("document_id", docId);
        payload.put("version", version);
        payload.put("document_title", title);
        payload.put("document_type", docType);
        payload.put("source_format", format);
        payload.put("business_status", "APPROVED");
        payload.put("tenant_id", tenant);
        payload.put("owner_id", "rest-user");
        payload.put("classification", classif);

        ArrayNode rolesArray = payload.putArray("allowed_roles");
        for (String role : roles) {
            rolesArray.add(role.trim());
        }

        payload.put("s3_bucket", s3BucketName);
        payload.put("s3_raw_key", s3RawKey);

        String messageBody = mapper.writeValueAsString(payload);

        log.info("[DocumentService] Enqueueing claim-check message to SQS: {}", parsingQueueUrl);
        sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(parsingQueueUrl)
                .messageBody(messageBody)
                .build());

        return new DocumentUploadResponse(
                docId,
                version,
                title,
                format,
                s3RawKey,
                "QUEUED",
                tenant,
                roles,
                Instant.now().toString()
        );
    }

    public DocumentStatusResponse getDocumentStatus(String documentId) {
        String sql = """
            SELECT document_id, version, document_title, document_type, lifecycle_status,
                   business_status, tenant_id, classification, s3_raw_key, s3_ir_key, updated_at
              FROM documents
             WHERE document_id = ?
             ORDER BY version DESC
             LIMIT 1
        """;

        try (Connection conn = DriverManager.getConnection(DbConfig.getUrl(), DbConfig.getUser(), DbConfig.getPassword());
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, documentId);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new DocumentStatusResponse(
                            rs.getString("document_id"),
                            rs.getInt("version"),
                            rs.getString("document_title"),
                            rs.getString("document_type"),
                            rs.getString("lifecycle_status"),
                            rs.getString("business_status"),
                            rs.getString("tenant_id"),
                            rs.getString("classification"),
                            rs.getString("s3_raw_key"),
                            rs.getString("s3_ir_key"),
                            rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toInstant().toString() : null
                    );
                }
            }
        } catch (Exception e) {
            log.error("[DocumentService] Failed to query document status for {}: {}", documentId, e.getMessage());
            throw new RuntimeException("Database error retrieving document status: " + e.getMessage(), e);
        }

        return null;
    }

    private String extractFormat(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex != -1 && dotIndex < filename.length() - 1) {
            return filename.substring(dotIndex + 1).toUpperCase();
        }
        return "UNKNOWN";
    }

    private String sanitizeFilename(String filename) {
        return filename.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
