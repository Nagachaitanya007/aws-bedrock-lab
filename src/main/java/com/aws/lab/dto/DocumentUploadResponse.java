package com.aws.lab.dto;

import java.util.List;

public record DocumentUploadResponse(
        String documentId,
        int version,
        String documentTitle,
        String sourceFormat,
        String s3RawKey,
        String status,
        String tenantId,
        List<String> allowedRoles,
        String queuedAt
) {}
