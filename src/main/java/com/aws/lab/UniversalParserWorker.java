package com.aws.lab;

import com.aws.lab.DbConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.MessageDigest;
import java.util.HexFormat;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

public class UniversalParserWorker {

    private static final Region REGION = Region.US_EAST_1;
    private static final String PARSING_QUEUE_URL = System.getenv().getOrDefault("PARSING_QUEUE_URL", "https://sqs.us-east-1.amazonaws.com/564956048142/aegis-parsing-queue");
    private static final String INDEXING_QUEUE_URL = System.getenv().getOrDefault("INDEXING_QUEUE_URL", "https://sqs.us-east-1.amazonaws.com/564956048142/aegis-indexing-queue");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) {
        System.out.println("[UniversalParserWorker] Worker started. Polling: " + PARSING_QUEUE_URL);

        S3Client s3 = S3Client.builder().region(REGION).build();
        SqsClient sqs = SqsClient.builder().region(REGION).build();

        try {
            ReceiveMessageRequest receiveRequest = ReceiveMessageRequest.builder()
                    .queueUrl(PARSING_QUEUE_URL)
                    .maxNumberOfMessages(1)
                    .waitTimeSeconds(10)
                    .build();

            List<Message> messages = sqs.receiveMessage(receiveRequest).messages();
            if (messages.isEmpty()) {
                System.out.println("[UniversalParserWorker] No messages in parsing queue.");
                return;
            }

            for (Message msg : messages) {
                processMessage(s3, sqs, msg);
            }
        } catch (Exception e) {
            System.err.println("[UniversalParserWorker] Fatal error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void processMessage(S3Client s3, SqsClient sqs, Message message) throws Exception {
        System.out.println("[UniversalParserWorker] Processing message ID: " + message.messageId());
        JsonNode root = MAPPER.readTree(message.body());

        String docId = root.get("document_id").asText();
        int version = root.has("version") ? root.get("version").asInt() : 1;
        String docTitle = root.has("document_title") ? root.get("document_title").asText() : "Untitled";
        String docType = root.has("document_type") ? root.get("document_type").asText() : "GENERAL";
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

        // 3. Parse Markdown into Intermediate Representation (IR)
        String markdownText = new String(rawBytes, StandardCharsets.UTF_8);
        ObjectNode irRoot = MAPPER.createObjectNode();
        irRoot.put("document_id", docId);
        irRoot.put("version", version);
        irRoot.put("title", docTitle);
        irRoot.put("content_hash", contentHash);

        ArrayNode chunksArray = irRoot.putArray("chunks");
        String[] sections = markdownText.split("(?=## )");
        int chunkIndex = 0;

        for (String section : sections) {
            String trimmed = section.trim();
            if (trimmed.isEmpty()) continue;

            String sectionTitle = "Overview";
            if (trimmed.startsWith("## ")) {
                int firstLineEnd = trimmed.indexOf('\n');
                if (firstLineEnd != -1) {
                    sectionTitle = trimmed.substring(3, firstLineEnd).trim();
                } else {
                    sectionTitle = trimmed.substring(3).trim();
                }
            }

            ObjectNode chunkNode = chunksArray.addObject();
            chunkNode.put("chunk_index", chunkIndex++);
            chunkNode.put("section_title", sectionTitle);
            chunkNode.put("content", trimmed);
        }

        // 4. Save Canonical IR to S3
        String irJson = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(irRoot);
        s3.putObject(
                b -> b.bucket(s3Bucket).key(irKey),
                RequestBody.fromString(irJson, StandardCharsets.UTF_8)
        );
        System.out.println("[UniversalParserWorker] Canonical IR stored at s3://" + s3Bucket + "/" + irKey);

        // 5. Send Claim-Check message to Indexing Queue
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

        // 6. Delete message from parsing queue
        sqs.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(PARSING_QUEUE_URL)
                .receiptHandle(message.receiptHandle())
                .build());
        System.out.println("[UniversalParserWorker] Parsed and enqueued successfully.");
    }
}