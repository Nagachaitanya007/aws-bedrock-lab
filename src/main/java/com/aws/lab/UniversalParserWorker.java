package com.aws.lab;

import com.aws.lab.DbConfig;
import com.aws.lab.extractor.DocumentExtractor;
import com.aws.lab.extractor.ExtractedChunk;
import com.aws.lab.extractor.ExtractorFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public class UniversalParserWorker {

    private static final Region REGION = Region.US_EAST_1;
    private static final String PARSING_QUEUE_URL = System.getenv().getOrDefault("PARSING_QUEUE_URL", "https://sqs.us-east-1.amazonaws.com/564956048142/doclab-parsing-queue");
    private static final String INDEXING_QUEUE_URL = System.getenv().getOrDefault("INDEXING_QUEUE_URL", "https://sqs.us-east-1.amazonaws.com/564956048142/doclab-indexing-queue");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static int drainQueue(S3Client s3, SqsClient sqs) {
        return drainQueue(s3, sqs, 1);
    }

    public static int drainQueue(S3Client s3, SqsClient sqs, int waitTimeSeconds) {
        int totalProcessed = 0;
        while (true) {
            ReceiveMessageRequest receiveRequest = ReceiveMessageRequest.builder()
                    .queueUrl(PARSING_QUEUE_URL)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(waitTimeSeconds)
                    .build();

            List<Message> messages = sqs.receiveMessage(receiveRequest).messages();
            if (messages.isEmpty()) {
                break;
            }

            for (Message msg : messages) {
                try {
                    processMessage(s3, sqs, msg);
                    totalProcessed++;
                } catch (Exception e) {
                    System.err.println("[UniversalParserWorker] Error processing message " + msg.messageId() + ": " + e.getMessage());
                }
            }
        }
        return totalProcessed;
    }

    public static void main(String[] args) {
        System.out.println("[UniversalParserWorker] Worker started. Draining queue: " + PARSING_QUEUE_URL);

        try (S3Client s3 = S3Client.builder().region(REGION).build();
             SqsClient sqs = SqsClient.builder().region(REGION).build()) {

            int processed = drainQueue(s3, sqs, 2);
            if (processed == 0) {
                System.out.println("[UniversalParserWorker] No messages in parsing queue.");
            } else {
                System.out.println("[UniversalParserWorker] Successfully processed and drained " + processed + " message(s).");
            }
        } catch (Exception e) {
            System.err.println("[UniversalParserWorker] Fatal error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    public static void processMessage(S3Client s3, SqsClient sqs, Message message) throws Exception {
        System.out.println("[UniversalParserWorker] Processing message ID: " + message.messageId());
        JsonNode root = MAPPER.readTree(message.body());

        String docId = root.get("document_id").asText();
        int version = root.has("version") ? root.get("version").asInt() : 1;
        String docTitle = root.has("document_title") ? root.get("document_title").asText() : "Untitled";
        String docType = root.has("document_type") ? root.get("document_type").asText() : "GENERAL";
        String sourceFormat = root.has("source_format") ? root.get("source_format").asText() : "UNKNOWN";
        String businessStatus = root.has("business_status") ? root.get("business_status").asText() : "APPROVED";
        String tenantId = root.has("tenant_id") ? root.get("tenant_id").asText() : "default";
        String ownerId = root.has("owner_id") ? root.get("owner_id").asText() : "system";
        String classification = root.has("classification") ? root.get("classification").asText() : "INTERNAL";

        List<String> allowedRoles = new ArrayList<>();
        if (root.has("allowed_roles") && root.get("allowed_roles").isArray()) {
            for (JsonNode roleNode : root.get("allowed_roles")) {
                allowedRoles.add(roleNode.asText());
            }
        }
        if (allowedRoles.isEmpty()) {
            allowedRoles.add("ENGINEERING");
        }

        String s3Bucket = root.get("s3_bucket").asText();
        String s3RawKey = root.get("s3_raw_key").asText();

        // 1. Download raw content and compute SHA-256 hash
        byte[] rawBytes = s3.getObjectAsBytes(b -> b.bucket(s3Bucket).key(s3RawKey)).asByteArray();
        byte[] hashBytes = MessageDigest.getInstance("SHA-256").digest(rawBytes);
        String contentHash = HexFormat.of().formatHex(hashBytes);
        String irKey = "ir/" + docId + "-v" + version + ".json";

        // 2. Register / Update entry in documents table as PROCESSING
        String upsertDocSql = """
            INSERT INTO documents (
                document_id, version, content_hash, document_title, document_type,
                business_status, lifecycle_status, tenant_id, owner_id,
                classification, allowed_roles, s3_raw_key, s3_ir_key, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, 'PROCESSING', ?, ?, ?, ?::text[], ?, ?, NOW())
            ON CONFLICT (document_id, version) DO UPDATE SET
                content_hash = EXCLUDED.content_hash,
                document_title = EXCLUDED.document_title,
                document_type = EXCLUDED.document_type,
                business_status = EXCLUDED.business_status,
                lifecycle_status = 'PROCESSING',
                tenant_id = EXCLUDED.tenant_id,
                owner_id = EXCLUDED.owner_id,
                classification = EXCLUDED.classification,
                allowed_roles = EXCLUDED.allowed_roles,
                s3_raw_key = EXCLUDED.s3_raw_key,
                s3_ir_key = EXCLUDED.s3_ir_key,
                updated_at = NOW();
        """;

        try (Connection conn = DriverManager.getConnection(DbConfig.getUrl(), DbConfig.getUser(), DbConfig.getPassword());
             PreparedStatement ps = conn.prepareStatement(upsertDocSql)) {
            ps.setString(1, docId);
            ps.setInt(2, version);
            ps.setString(3, contentHash);
            ps.setString(4, docTitle);
            ps.setString(5, docType);
            ps.setString(6, businessStatus);
            ps.setString(7, tenantId);
            ps.setString(8, ownerId);
            ps.setString(9, classification);
            ps.setArray(10, conn.createArrayOf("text", allowedRoles.toArray(new String[0])));
            ps.setString(11, s3RawKey);
            ps.setString(12, irKey);
            ps.executeUpdate();
            System.out.println("[UniversalParserWorker] Document registered in DB with status: PROCESSING");
        }

        // 3. Universal Extractor Selection (PDF, Word, Excel, JSON, Markdown/Text)
        DocumentExtractor extractor = ExtractorFactory.getExtractor(sourceFormat, s3RawKey);
        System.out.printf("[UniversalParserWorker] Parsing %s (%d bytes) using %s (format: %s)%n",
                s3RawKey, rawBytes.length, extractor.getClass().getSimpleName(), sourceFormat);

        List<ExtractedChunk> extractedChunks;
        try {
            extractedChunks = extractor.extract(rawBytes, s3RawKey);
        } catch (Exception ex) {
            System.err.println("[UniversalParserWorker] Extraction error for " + docId + ": " + ex.getMessage());
            markDocumentFailed(docId, version, ex.getMessage());
            // Delete poisoned message to prevent infinite retry loops
            sqs.deleteMessage(DeleteMessageRequest.builder()
                    .queueUrl(PARSING_QUEUE_URL)
                    .receiptHandle(message.receiptHandle())
                    .build());
            return;
        }

        // 4. Build Canonical Intermediate Representation (IR)
        ObjectNode irRoot = MAPPER.createObjectNode();
        irRoot.put("document_id", docId);
        irRoot.put("version", version);
        irRoot.put("title", docTitle);
        irRoot.put("source_format", sourceFormat);
        irRoot.put("content_hash", contentHash);

        ArrayNode chunksArray = irRoot.putArray("chunks");
        int chunkIndex = 0;

        for (ExtractedChunk chunk : extractedChunks) {
            ObjectNode chunkNode = chunksArray.addObject();
            chunkNode.put("chunk_index", chunkIndex++);
            chunkNode.put("section_title", chunk.sectionTitle());
            chunkNode.put("content", chunk.content());
        }

        // 5. Save Canonical IR to S3
        String irJson = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(irRoot);
        s3.putObject(
                b -> b.bucket(s3Bucket).key(irKey),
                RequestBody.fromString(irJson, StandardCharsets.UTF_8)
        );
        System.out.println("[UniversalParserWorker] Canonical IR stored at s3://" + s3Bucket + "/" + irKey
                + " with " + chunkIndex + " chunks.");

        // 6. Send Claim-Check message to Indexing Queue
        ObjectNode indexMsg = MAPPER.createObjectNode();
        indexMsg.put("document_id", docId);
        indexMsg.put("version", version);
        indexMsg.put("content_hash", contentHash);
        indexMsg.put("document_title", docTitle);
        indexMsg.put("tenant_id", tenantId);
        indexMsg.put("classification", classification);
        indexMsg.put("s3_bucket", s3Bucket);
        indexMsg.put("s3_ir_key", irKey);

        sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(INDEXING_QUEUE_URL)
                .messageBody(MAPPER.writeValueAsString(indexMsg))
                .build());
        System.out.println("[UniversalParserWorker] Forwarded claim check to Indexing Queue.");

        // 7. Delete message from parsing queue
        sqs.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(PARSING_QUEUE_URL)
                .receiptHandle(message.receiptHandle())
                .build());
        System.out.println("[UniversalParserWorker] Parsed and enqueued successfully.");
    }

    private static void markDocumentFailed(String docId, int version, String errorReason) {
        String sql = "UPDATE documents SET lifecycle_status = 'FAILED', updated_at = NOW() WHERE document_id = ? AND version = ?";
        try (Connection conn = DriverManager.getConnection(DbConfig.getUrl(), DbConfig.getUser(), DbConfig.getPassword());
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, docId);
            ps.setInt(2, version);
            ps.executeUpdate();
            System.err.println("[UniversalParserWorker] Marked " + docId + " as FAILED in database: " + errorReason);
        } catch (Exception e) {
            System.err.println("[UniversalParserWorker] Error updating failure status: " + e.getMessage());
        }
    }
}
