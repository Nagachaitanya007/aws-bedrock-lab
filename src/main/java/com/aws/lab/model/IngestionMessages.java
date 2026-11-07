package com.aws.lab.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

public class IngestionMessages {

    // Emitted to doclab-parsing-queue
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ParsingTaskMessage(
            String documentId,
            String s3Bucket,
            String s3RawKey,
            String documentType,
            String sourceFormat,
            Map<String, Object> metadata
    ) {}

    // Emitted to doclab-indexing-queue (Claim-Check pattern)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IndexingTaskMessage(
            String documentId,
            String s3Bucket,
            String s3CanonicalIrKey,
            String documentType
    ) {}
}