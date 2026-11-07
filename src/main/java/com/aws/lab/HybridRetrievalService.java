package com.aws.lab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HybridRetrievalService {

    private final Region region;
    private final BedrockRuntimeClient bedrock;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record AuthContext(String tenantId, List<String> userRoles, String classification) {
        public static AuthContext defaultEngineering() {
            return new AuthContext("engineering-dept", List.of("ENGINEERING"), "INTERNAL");
        }
    }

    public static class ScoredChunk {
        public int id;
        public String documentTitle;
        public String sectionTitle;
        public String content;
        public double denseRankScore;
        public double sparseRankScore;
        public double rrfScore;
        public double rerankerScore;

        public ScoredChunk(int id, String documentTitle, String sectionTitle, String content) {
            this.id = id;
            this.documentTitle = documentTitle;
            this.sectionTitle = sectionTitle;
            this.content = content;
        }

        public String getFullContextText() {
            return String.format("Document: %s | Section: %s\n%s", documentTitle, sectionTitle, content);
        }
    }

    public HybridRetrievalService(Region region) {
        this.region = region;
        this.bedrock = BedrockRuntimeClient.builder().region(region).build();
    }

    public HybridRetrievalService() {
        this(Region.US_EAST_1);
    }

    public List<ScoredChunk> retrieve(String queryText, int topK) throws Exception {
        return retrieve(queryText, topK, AuthContext.defaultEngineering());
    }

    /**
     * Hybrid Reciprocal Rank Fusion (Dense Vector + BM25 Lexical) with Governance.
     */
    public List<ScoredChunk> retrieve(String queryText, int topK, AuthContext auth) throws Exception {
        String queryEmbedding = generateEmbedding(this.bedrock, queryText);

        Map<Integer, ScoredChunk> chunkMap = new HashMap<>();

        try (Connection conn = DriverManager.getConnection(DbConfig.getUrl(), DbConfig.getUser(), DbConfig.getPassword())) {

            Array rolesArray = conn.createArrayOf("text", auth.userRoles().toArray(new String[0]));

            // 1. Dense Vector Search with Governance Pre-Filtering
            String denseSql = """
                SELECT c.id, c.document_title, c.section_title, c.content,
                       (1 - (c.embedding <=> ?::vector)) AS similarity
                  FROM document_chunks c
                  JOIN documents d ON c.document_id = d.document_id AND c.document_version = d.version
                 WHERE d.lifecycle_status = 'ACTIVE'
                   AND d.tenant_id = ?
                   AND d.allowed_roles && ?::text[]
                 ORDER BY c.embedding <=> ?::vector
                 LIMIT ?;
            """;

            try (PreparedStatement ps = conn.prepareStatement(denseSql)) {
                ps.setString(1, queryEmbedding);
                ps.setString(2, auth.tenantId());
                ps.setArray(3, rolesArray);
                ps.setString(4, queryEmbedding);
                ps.setInt(5, Math.max(topK * 2, 20));

                try (ResultSet rs = ps.executeQuery()) {
                    int rank = 1;
                    while (rs.next()) {
                        int id = rs.getInt("id");
                        String docTitle = rs.getString("document_title");
                        String secTitle = rs.getString("section_title");
                        String content = rs.getString("content");

                        ScoredChunk sc = chunkMap.computeIfAbsent(id, k ->
                                new ScoredChunk(id, docTitle, secTitle, content)
                        );
                        sc.denseRankScore = 1.0 / (60.0 + rank);
                        rank++;
                    }
                }
            }

            // 2. Sparse Lexical Search with Governance Pre-Filtering
            String sparseSql = """
                SELECT c.id, c.document_title, c.section_title, c.content,
                       ts_rank_cd(to_tsvector('english', c.content), plainto_tsquery('english', ?)) AS rank_score
                  FROM document_chunks c
                  JOIN documents d ON c.document_id = d.document_id AND c.document_version = d.version
                 WHERE d.lifecycle_status = 'ACTIVE'
                   AND d.tenant_id = ?
                   AND d.allowed_roles && ?::text[]
                   AND to_tsvector('english', c.content) @@ plainto_tsquery('english', ?)
                 ORDER BY rank_score DESC
                 LIMIT ?;
            """;

            try (PreparedStatement ps = conn.prepareStatement(sparseSql)) {
                ps.setString(1, queryText);
                ps.setString(2, auth.tenantId());
                ps.setArray(3, rolesArray);
                ps.setString(4, queryText);
                ps.setInt(5, Math.max(topK * 2, 20));

                try (ResultSet rs = ps.executeQuery()) {
                    int rank = 1;
                    while (rs.next()) {
                        int id = rs.getInt("id");
                        String docTitle = rs.getString("document_title");
                        String secTitle = rs.getString("section_title");
                        String content = rs.getString("content");

                        ScoredChunk sc = chunkMap.computeIfAbsent(id, k ->
                                new ScoredChunk(id, docTitle, secTitle, content)
                        );
                        sc.sparseRankScore = 1.0 / (60.0 + rank);
                        rank++;
                    }
                }
            }
        }

        // 3. Reciprocal Rank Fusion
        List<ScoredChunk> fusedResults = new ArrayList<>(chunkMap.values());
        for (ScoredChunk sc : fusedResults) {
            sc.rrfScore = sc.denseRankScore + sc.sparseRankScore;
        }

        fusedResults.sort((a, b) -> Double.compare(b.rrfScore, a.rrfScore));

        return fusedResults.subList(0, Math.min(topK, fusedResults.size()));
    }

    /**
     * Pure Dense Vector Search (for benchmark comparison).
     */
    public List<ScoredChunk> retrieveDense(String queryText, int topK, AuthContext auth) throws Exception {
        String queryEmbedding = generateEmbedding(this.bedrock, queryText);
        List<ScoredChunk> results = new ArrayList<>();

        try (Connection conn = DriverManager.getConnection(DbConfig.getUrl(), DbConfig.getUser(), DbConfig.getPassword())) {
            Array rolesArray = conn.createArrayOf("text", auth.userRoles().toArray(new String[0]));
            String sql = """
                SELECT c.id, c.document_title, c.section_title, c.content,
                       (1 - (c.embedding <=> ?::vector)) AS similarity
                  FROM document_chunks c
                  JOIN documents d ON c.document_id = d.document_id AND c.document_version = d.version
                 WHERE d.lifecycle_status = 'ACTIVE'
                   AND d.tenant_id = ?
                   AND d.allowed_roles && ?::text[]
                 ORDER BY c.embedding <=> ?::vector
                 LIMIT ?;
            """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, queryEmbedding);
                ps.setString(2, auth.tenantId());
                ps.setArray(3, rolesArray);
                ps.setString(4, queryEmbedding);
                ps.setInt(5, topK);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ScoredChunk sc = new ScoredChunk(
                                rs.getInt("id"),
                                rs.getString("document_title"),
                                rs.getString("section_title"),
                                rs.getString("content")
                        );
                        sc.denseRankScore = rs.getDouble("similarity");
                        results.add(sc);
                    }
                }
            }
        }
        return results;
    }

    /**
     * Pure Sparse BM25 Search (for benchmark comparison).
     */
    public List<ScoredChunk> retrieveSparse(String queryText, int topK, AuthContext auth) throws Exception {
        List<ScoredChunk> results = new ArrayList<>();

        try (Connection conn = DriverManager.getConnection(DbConfig.getUrl(), DbConfig.getUser(), DbConfig.getPassword())) {
            Array rolesArray = conn.createArrayOf("text", auth.userRoles().toArray(new String[0]));
            String sql = """
                SELECT c.id, c.document_title, c.section_title, c.content,
                       ts_rank_cd(to_tsvector('english', c.content), plainto_tsquery('english', ?)) AS rank_score
                  FROM document_chunks c
                  JOIN documents d ON c.document_id = d.document_id AND c.document_version = d.version
                 WHERE d.lifecycle_status = 'ACTIVE'
                   AND d.tenant_id = ?
                   AND d.allowed_roles && ?::text[]
                   AND to_tsvector('english', c.content) @@ plainto_tsquery('english', ?)
                 ORDER BY rank_score DESC
                 LIMIT ?;
            """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, queryText);
                ps.setString(2, auth.tenantId());
                ps.setArray(3, rolesArray);
                ps.setString(4, queryText);
                ps.setInt(5, topK);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ScoredChunk sc = new ScoredChunk(
                                rs.getInt("id"),
                                rs.getString("document_title"),
                                rs.getString("section_title"),
                                rs.getString("content")
                        );
                        sc.sparseRankScore = rs.getDouble("rank_score");
                        results.add(sc);
                    }
                }
            }
        }
        return results;
    }

    public String generateEmbedding(BedrockRuntimeClient bedrock, String text) throws Exception {
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
