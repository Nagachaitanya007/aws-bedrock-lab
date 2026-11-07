package com.aws.lab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sagemakerruntime.SageMakerRuntimeClient;
import software.amazon.awssdk.services.sagemakerruntime.model.InvokeEndpointRequest;
import software.amazon.awssdk.services.sagemakerruntime.model.InvokeEndpointResponse;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class SageMakerRerankerService {

    private static final Logger log = LoggerFactory.getLogger(SageMakerRerankerService.class);

    private final SageMakerRuntimeClient client;
    private final String endpointName;
    private final ObjectMapper mapper;

    public SageMakerRerankerService(String endpointName, Region region) {
        this.endpointName = endpointName;
        this.client = SageMakerRuntimeClient.builder()
                .region(region)
                .build();
        this.mapper = new ObjectMapper();
    }

    public record ScoredDocument(String text, double score) {}

    /**
     * Cross-Encoder Reranking of HybridRetrievalService chunks.
     * Takes candidate chunks (e.g. 15), scores each (query, chunk) pair using
     * BAAI/bge-reranker-base on SageMaker, and returns the topK chunks sorted by relevance score.
     */
    public List<HybridRetrievalService.ScoredChunk> rerankChunks(
            String query,
            List<HybridRetrievalService.ScoredChunk> candidates,
            int topK
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        if (candidates.size() <= 1) {
            return candidates;
        }

        try {
            List<Map<String, Object>> inputs = new ArrayList<>();
            for (HybridRetrievalService.ScoredChunk c : candidates) {
                String textPair = c.getFullContextText();
                inputs.add(Map.of("text", query, "text_pair", textPair));
            }

            String payloadJson = mapper.writeValueAsString(Map.of("inputs", inputs));

            InvokeEndpointRequest request = InvokeEndpointRequest.builder()
                    .endpointName(endpointName)
                    .contentType("application/json")
                    .body(SdkBytes.fromString(payloadJson, StandardCharsets.UTF_8))
                    .build();

            long start = System.currentTimeMillis();
            InvokeEndpointResponse response = client.invokeEndpoint(request);
            long latency = System.currentTimeMillis() - start;

            String responseBody = response.body().asString(StandardCharsets.UTF_8);
            JsonNode results = mapper.readTree(responseBody);

            for (int i = 0; i < results.size() && i < candidates.size(); i++) {
                double score = results.get(i).path("score").asDouble(0.0);
                candidates.get(i).rerankerScore = score;
            }

            List<HybridRetrievalService.ScoredChunk> reranked = new ArrayList<>(candidates);
            reranked.sort(Comparator.comparingDouble((HybridRetrievalService.ScoredChunk c) -> c.rerankerScore).reversed());

            double bestScore = reranked.isEmpty() ? 0.0 : reranked.get(0).rerankerScore;
            log.info("[SageMaker Reranker ({}ms)] Reranked {} candidates down to top {} (top score: {})",
                    latency, candidates.size(), Math.min(topK, reranked.size()), String.format("%.4f", bestScore));

            return reranked.subList(0, Math.min(topK, reranked.size()));

        } catch (Exception e) {
            log.warn("[SageMaker Reranker] Scoring failed, falling back to RRF ordering: {}", e.getMessage());
            return candidates.subList(0, Math.min(topK, candidates.size()));
        }
    }

    public List<ScoredDocument> rerank(String query, List<String> passages) {
        try {
            List<Map<String, Object>> inputs = new ArrayList<>();
            for (String passage : passages) {
                inputs.add(Map.of("text", query, "text_pair", passage));
            }
            String payloadJson = mapper.writeValueAsString(Map.of("inputs", inputs));

            InvokeEndpointRequest request = InvokeEndpointRequest.builder()
                    .endpointName(endpointName)
                    .contentType("application/json")
                    .body(SdkBytes.fromString(payloadJson, StandardCharsets.UTF_8))
                    .build();

            InvokeEndpointResponse response = client.invokeEndpoint(request);
            String responseBody = response.body().asString(StandardCharsets.UTF_8);

            JsonNode results = mapper.readTree(responseBody);
            List<ScoredDocument> scored = new ArrayList<>();

            for (int i = 0; i < results.size(); i++) {
                double score = results.get(i).path("score").asDouble();
                scored.add(new ScoredDocument(passages.get(i), score));
            }

            scored.sort(Comparator.comparingDouble(ScoredDocument::score).reversed());
            return scored;

        } catch (Exception e) {
            log.warn("[SageMaker Reranker] Scoring failed, falling back: {}", e.getMessage());
            return passages.stream().map(p -> new ScoredDocument(p, 0.0)).toList();
        }
    }
}
