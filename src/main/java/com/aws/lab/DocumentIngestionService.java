package com.aws.lab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgvector.PGvector;
import software.amazon.awssdk.regions.Region;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;

public class DocumentIngestionService {

    private static final String DB_URL = DbConfig.getUrl();
    private static final String DB_USER = DbConfig.getUser();
    private static final String DB_PASS = DbConfig.getPassword();

    public record IngestChunk(
            String documentTitle,
            String sectionTitle,
            int chunkIndex,
            String content,
            Map<String, Object> metadata
    ) {}

    public static void main(String[] args) {
        System.out.println(">>> Starting Ingestion Pipeline into pgvector...");

        BedrockEmbeddingService embeddingService = new BedrockEmbeddingService(Region.US_EAST_1);
        ObjectMapper mapper = new ObjectMapper();

        // High-signal incident post-mortems and architecture ADRs
        List<IngestChunk> corpus = List.of(
                new IngestChunk(
                        "INC-2026-042: SQS Message Ingestion Starvation",
                        "Root Cause Analysis",
                        1,
                        "During high-traffic peak load, the checkout-api experienced a connection starvation failure on the SQS listener pool. Visibility timeout mismatches caused recurring message retries, overwhelming downstream workers and throwing SocketTimeoutException across worker threads.",
                        Map.of("service", "checkout-api", "severity", "SEV-1", "category", "messaging", "error_code", "SocketTimeoutException")
                ),
                new IngestChunk(
                        "INC-2026-042: SQS Message Ingestion Starvation",
                        "Resolution & Remediation",
                        2,
                        "The engineering team adjusted the SQS queue visibility timeout from 30 seconds to 120 seconds. An Amazon SQS Dead-Letter Queue (DLQ) with maxReceiveCount=3 was attached to isolate toxic messages after repeated processing failures.",
                        Map.of("service", "checkout-api", "severity", "SEV-1", "category", "messaging", "remediation", "DLQ")
                ),
                new IngestChunk(
                        "ADR-014: Session State Management",
                        "Context and Architectural Decision",
                        1,
                        "We selected Amazon ElastiCache Redis for distributed session caching. Redis maintains a pair-aligned sliding-window turn history with TTL-based key expiration, isolating conversational state from stateless containerized compute.",
                        Map.of("service", "session-manager", "severity", "INFO", "category", "caching", "component", "ElastiCache")
                ),
                new IngestChunk(
                        "INC-2026-088: Payment Webhook Gateway 504 Timeout",
                        "Root Cause Analysis",
                        1,
                        "Third-party payment webhooks triggered database connection pool exhaustion on the primary PostgreSQL instance. Synchronous external API calls within the database transaction block caused connection hold times to spike from 15ms to 8000ms, leading to HTTP 504 Gateway Timeouts.",
                        Map.of("service", "payment-service", "severity", "SEV-1", "category", "database", "error_code", "504")
                )
        );

        String insertSql = """
            INSERT INTO document_chunks (document_title, section_title, chunk_index, content, metadata, embedding)
            VALUES (?, ?, ?, ?, ?::jsonb, ?);
        """;

        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
             PreparedStatement ps = conn.prepareStatement(insertSql)) {

            PGvector.addVectorType(conn);

            for (IngestChunk chunk : corpus) {
                System.out.printf("[Embedding] Generating vector for '%s' - '%s'...\n", chunk.documentTitle(), chunk.sectionTitle());
                float[] vector = embeddingService.generateEmbedding(chunk.content());

                ps.setString(1, chunk.documentTitle());
                ps.setString(2, chunk.sectionTitle());
                ps.setInt(3, chunk.chunkIndex());
                ps.setString(4, chunk.content());
                ps.setString(5, mapper.writeValueAsString(chunk.metadata()));
                ps.setObject(6, new PGvector(vector));

                ps.executeUpdate();
            }

            System.out.println(">>> Ingestion complete! All chunks embedded and saved to PostgreSQL.");

        } catch (Exception e) {
            System.err.println("[Ingestion Error] " + e.getMessage());
            e.printStackTrace();
        }
    }
}