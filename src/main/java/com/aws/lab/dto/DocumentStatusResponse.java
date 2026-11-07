package com.aws.lab.dto;

public record DocumentStatusResponse(
        String documentId,
        int version,
        String documentTitle,
        String documentType,
        String lifecycleStatus,
        String businessStatus,
        String tenantId,
        String classification,
        String s3RawKey,
        String s3IrKey,
        String updatedAt
) {}
