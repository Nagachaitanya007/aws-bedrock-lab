package com.aws.lab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.io.File;
import java.nio.file.Files;

public class DocumentProducer {

    private static final Region REGION = Region.US_EAST_1;
    private static final String S3_BUCKET = System.getenv().getOrDefault("S3_BUCKET_NAME", "aegis-rag-docs-c3fe55a3");
    private static final String PARSING_QUEUE_URL = System.getenv().getOrDefault("PARSING_QUEUE_URL", "https://sqs.us-east-1.amazonaws.com/564956048142/aegis-parsing-queue");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        S3Client s3 = S3Client.builder().region(REGION).build();
        SqsClient sqs = SqsClient.builder().region(REGION).build();

        // Stable identity for ADR-025
        String docId = "doc-adr-025";
        int version = 1;
        String rawKey = "raw/" + docId + "-v" + version + ".md";

        File docFile = new File("E:\\aws-bedrock-lab\\documents\\ADR-025-Kafka-Outbox.md");
        byte[] docBytes = Files.readAllBytes(docFile.toPath());

        System.out.println("[DocumentProducer] Uploading " + docFile.getName() + " to s3://" + S3_BUCKET + "/" + rawKey);
        s3.putObject(
                PutObjectRequest.builder().bucket(S3_BUCKET).key(rawKey).build(),
                RequestBody.fromBytes(docBytes)
        );

        // Build governance payload
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("document_id", docId);
        payload.put("version", version);
        payload.put("document_title", "ADR-025: Migration to Apache Kafka for Outbox Event Streaming");
        payload.put("document_type", "ARCHITECTURE_DECISION_RECORD");
        payload.put("business_status", "APPROVED");
        payload.put("tenant_id", "engineering-dept");
        payload.put("owner_id", "team-order-processing");
        payload.put("classification", "INTERNAL");

        ArrayNode roles = payload.putArray("allowed_roles");
        roles.add("ENGINEERING");
        roles.add("ARCHITECT");

        payload.put("s3_bucket", S3_BUCKET);
        payload.put("s3_raw_key", rawKey);

        String messageBody = MAPPER.writeValueAsString(payload);

        System.out.println("[DocumentProducer] Sending Claim-Check message to Parsing SQS Queue...");
        sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(PARSING_QUEUE_URL)
                .messageBody(messageBody)
                .build());

        System.out.println("[DocumentProducer] Ingestion triggered successfully for " + docId + " v" + version);
    }
}