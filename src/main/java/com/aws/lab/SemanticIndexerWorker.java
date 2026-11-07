package com.aws.lab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

public class SemanticIndexerWorker {

    private static final Region REGION = Region.US_EAST_1;
    private static final String INDEXING_QUEUE_URL = System.getenv().getOrDefault(
            "INDEXING_QUEUE_URL",
            "https://sqs.us-east-1.amazonaws.com/564956048142/doclab-indexing-queue"
    );
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static class ChunkRecord {
        String documentTitle;
        String sectionTitle;
        int chunkIndex;
        String content;
        String metadataJson;
        String embeddingString;
    }

    public static void main(String[] args) {
        System.out.println("[SemanticIndexerWorker] Worker started. Polling: " + INDEXING_QUEUE_URL);

        S3Client s3 = S3Client.builder().region(REGION).build();
        SqsClient sqs = SqsClient.builder().region(REGION).build();
        BedrockRuntimeClient bedrock = BedrockRuntimeClient.builder().region(REGION).build();

        try {
            ReceiveMessageRequest receiveRequest = ReceiveMessageRequest.builder()
                    .queueUrl(INDEXING_QUEUE_URL)
                    .maxNumberOfMessages(1)
                    .waitTimeSeconds(10)
                    .build();

            List<Message> messages = sqs.receiveMessage(receiveRequest).messages();
            if (messages.isEmpty()) {
                System.out.println("[SemanticIndexerWorker] No messages in indexing queue.");
                return;
            }

            for (Message msg : messages) {
                processMessage(s3, sqs, bedrock, msg);
            }
        } catch (Exception e) {
            System.err.println("[SemanticIndexerWorker] Fatal error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void processMessage(S3Client s3, SqsClient sqs, BedrockRuntimeClient bedrock, Message message) throws Exception {
        System.out.println("[SemanticIndexerWorker] Processing message ID: " + message.messageId());
        JsonNode root = MAPPER.readTree(message.body());

        String docId = root.get("document_id").asText();
        int version = root.has("version") ? root.get("version").asInt() : 1;
        String s3Bucket = root.get("s3_bucket").asText();
        String s3IrKey = root.get("s3_ir_key").asText();

        System.out.println("[SemanticIndexerWorker] Downloading IR from s3://" + s3Bucket + "/" + s3IrKey);
        byte[] irBytes = s3.getObjectAsBytes(b -> b.bucket(s3Bucket).key(s3IrKey)).asByteArray();
        JsonNode irJson = MAPPER.readTree(new String(irBytes, StandardCharsets.UTF_8));

        String docTitle = irJson.has("title") ? irJson.get("title").asText() : "Untitled";
        JsonNode chunksNode = irJson.get("chunks");

        List<ChunkRecord> chunkRecords = new ArrayList<>();

        if (chunksNode != null && chunksNode.isArray()) {
            for (JsonNode chunk : chunksNode) {
                ChunkRecord record = new ChunkRecord();
                record.documentTitle = docTitle;
                record.sectionTitle = chunk.get("section_title").asText();
                record.chunkIndex = chunk.get("chunk_index").asInt();
                record.content = chunk.get("content").asText();

                ObjectNode meta = MAPPER.createObjectNode();
                meta.put("document_id", docId);
                meta.put("document_version", version);
                meta.put("section_title", record.sectionTitle);
                record.metadataJson = MAPPER.writeValueAsString(meta);

                // Generate 1024-d embedding via Amazon Titan Text v2
                record.embeddingString = generateEmbedding(bedrock, record.content);
                chunkRecords.add(record);
            }
        }

        System.out.println("[SemanticIndexerWorker] Generated embeddings for " + chunkRecords.size() + " chunks.");

        // Atomic Database Transaction: Insert Chunks + Supersede Old + Activate New
        try (Connection conn = DriverManager.getConnection(DbConfig.getUrl(), DbConfig.getUser(), DbConfig.getPassword())) {
            conn.setAutoCommit(false);

            try {
                // 1. Idempotent cleanup for this exact version
                try (PreparedStatement delStmt = conn.prepareStatement(
                        "DELETE FROM document_chunks WHERE document_id = ? AND document_version = ?")) {
                    delStmt.setString(1, docId);
                    delStmt.setInt(2, version);
                    delStmt.executeUpdate();
                }

                // 2. Batch insert chunks
                String insertChunkSql = """
                    INSERT INTO document_chunks (
                        document_id, document_version, document_title, section_title,
                        chunk_index, content, metadata, embedding
                    ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::vector)
                """;

                try (PreparedStatement ps = conn.prepareStatement(insertChunkSql)) {
                    for (ChunkRecord chunk : chunkRecords) {
                        ps.setString(1, docId);
                        ps.setInt(2, version);
                        ps.setString(3, chunk.documentTitle);
                        ps.setString(4, chunk.sectionTitle);
                        ps.setInt(5, chunk.chunkIndex);
                        ps.setString(6, chunk.content);
                        ps.setString(7, chunk.metadataJson);
                        ps.setString(8, chunk.embeddingString);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }

                // 3. Mark older versions as SUPERSEDED
                String supersedeSql = """
                    UPDATE documents 
                       SET lifecycle_status = 'SUPERSEDED', updated_at = NOW() 
                     WHERE document_id = ? 
                       AND version < ? 
                       AND lifecycle_status = 'ACTIVE'
                """;
                try (PreparedStatement ps = conn.prepareStatement(supersedeSql)) {
                    ps.setString(1, docId);
                    ps.setInt(2, version);
                    ps.executeUpdate();
                }

                // 4. Activate current version
                String activateSql = """
                    UPDATE documents 
                       SET lifecycle_status = 'ACTIVE', updated_at = NOW() 
                     WHERE document_id = ? 
                       AND version = ?
                """;
                try (PreparedStatement ps = conn.prepareStatement(activateSql)) {
                    ps.setString(1, docId);
                    ps.setInt(2, version);
                    ps.executeUpdate();
                }

                conn.commit();
                System.out.println("[SemanticIndexerWorker] Successfully activated " + docId + " v" + version);

            } catch (Exception ex) {
                conn.rollback();
                System.err.println("[SemanticIndexerWorker] Indexing transaction failed. Setting FAILED status: " + ex.getMessage());

                // Isolated status update
                conn.setAutoCommit(true);
                try (PreparedStatement failStmt = conn.prepareStatement(
                        "UPDATE documents SET lifecycle_status = 'FAILED', updated_at = NOW() WHERE document_id = ? AND version = ?")) {
                    failStmt.setString(1, docId);
                    failStmt.setInt(2, version);
                    failStmt.executeUpdate();
                }
                throw ex;
            }
        }

        // 5. Delete message from indexing queue
        sqs.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(INDEXING_QUEUE_URL)
                .receiptHandle(message.receiptHandle())
                .build());
        System.out.println("[SemanticIndexerWorker] Finished processing message ID: " + message.messageId());
    }

    private static String generateEmbedding(BedrockRuntimeClient bedrock, String text) throws Exception {
        ObjectNode requestBody = MAPPER.createObjectNode();
        requestBody.put("inputText", text);
        requestBody.put("dimensions", 1024);
        requestBody.put("normalize", true);

        InvokeModelRequest request = InvokeModelRequest.builder()
                .modelId("amazon.titan-embed-text-v2:0")
                .contentType("application/json")
                .accept("application/json")
                .body(SdkBytes.fromUtf8String(MAPPER.writeValueAsString(requestBody)))
                .build();

        InvokeModelResponse response = bedrock.invokeModel(request);
        JsonNode responseJson = MAPPER.readTree(response.body().asUtf8String());
        JsonNode embeddingNode = responseJson.get("embedding");

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embeddingNode.size(); i++) {
            sb.append(embeddingNode.get(i).asDouble());
            if (i < embeddingNode.size() - 1) {
                sb.append(",");
            }
        }
        sb.append("]");
        return sb.toString();
    }
}