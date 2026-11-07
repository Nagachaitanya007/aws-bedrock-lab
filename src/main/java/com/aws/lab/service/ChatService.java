package com.aws.lab.service;

import com.aws.lab.HybridRetrievalService;
import com.aws.lab.SageMakerRerankerService;
import com.aws.lab.dto.ChatRequest;
import com.aws.lab.dto.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final int MAX_HISTORY_TURNS = 6;

    private final BedrockRuntimeClient bedrockClient;
    private final BedrockRuntimeAsyncClient asyncClient;
    private final HybridRetrievalService retrievalService;
    private final SageMakerRerankerService rerankerService;
    private final JedisPool jedisPool;

    @Value("${doclab.bedrock.model-id:amazon.nova-micro-v1:0}")
    private String modelId;

    @Value("${doclab.sagemaker.enabled:true}")
    private boolean rerankerEnabled;

    @Value("${doclab.sagemaker.candidate-count:15}")
    private int candidateCount;

    public ChatService(
            BedrockRuntimeClient bedrockClient,
            BedrockRuntimeAsyncClient asyncClient,
            HybridRetrievalService retrievalService,
            SageMakerRerankerService rerankerService,
            JedisPool jedisPool
    ) {
        this.bedrockClient = bedrockClient;
        this.asyncClient = asyncClient;
        this.retrievalService = retrievalService;
        this.rerankerService = rerankerService;
        this.jedisPool = jedisPool;
    }

    public void streamChat(
            String tenantId,
            List<String> userRoles,
            ChatRequest request,
            SseEmitter emitter
    ) {
        String sessionId = request.getEffectiveSessionId();
        String userQuery = request.query();
        int topK = request.getEffectiveTopK();
        boolean shouldRerank = request.isRerankRequested(rerankerEnabled);

        // 1. Rate Limiting Check
        if (!checkRateLimit(sessionId, 15, 60)) {
            try {
                emitter.send(SseEmitter.event().name("error").data("[Rate Limit Exceeded] Too many requests. Please wait a moment."));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
            return;
        }

        // 2. Exact Match Answer Cache Check
        String cacheKey = buildSecureCacheKey(tenantId, userRoles, userQuery, shouldRerank);
        String cachedAnswer = getFromCache(cacheKey);

        if (cachedAnswer != null) {
            log.info("[ChatService] Exact cache hit for sessionId: {}", sessionId);
            try {
                emitter.send(SseEmitter.event().name("cached").data(true));
                emitter.send(SseEmitter.event().name("token").data(cachedAnswer));
                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
            return;
        }

        // 3. Two-Stage Retrieval (RRF Candidate Recall + SageMaker Cross-Encoder Precision Reranking)
        HybridRetrievalService.AuthContext auth = new HybridRetrievalService.AuthContext(
                tenantId != null ? tenantId : "engineering-dept",
                userRoles != null && !userRoles.isEmpty() ? userRoles : List.of("ENGINEERING"),
                "INTERNAL"
        );
        List<HybridRetrievalService.ScoredChunk> chunks = retrieveAndRerank(userQuery, topK, shouldRerank, auth);

        // 4. Strict Guardrail: Prevent hallucination when zero authorized chunks are retrieved
        if (chunks.isEmpty()) {
            String noAccessMsg = "No authorized architectural or incident documentation found matching your query and role permissions.";
            try {
                emitter.send(SseEmitter.event().name("sources").data(List.of()));
                emitter.send(SseEmitter.event().name("token").data(noAccessMsg));
                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
            return;
        }

        // 5. Build Context & Tenant-Isolated Conversation History
        String effectiveTenant = (tenantId != null && !tenantId.isBlank()) ? tenantId : "default";
        String augmentedPrompt = buildAugmentedPrompt(userQuery, chunks);
        String historyKey = "session:" + effectiveTenant + ":" + sessionId + ":history";
        List<Message> messages = buildConverseMessages(historyKey, augmentedPrompt);

        SystemContentBlock systemPrompt = SystemContentBlock.fromText(
                "You are DocLab, an enterprise architecture and knowledge intelligence assistant. " +
                        "Provide direct, concise, technically rigorous answers citing document titles and section headers."
        );

        ConverseStreamRequest streamRequest = ConverseStreamRequest.builder()
                .modelId(modelId)
                .system(systemPrompt)
                .messages(messages)
                .inferenceConfig(InferenceConfiguration.builder()
                        .maxTokens(512)
                        .temperature(0.1f)
                        .build())
                .build();

        StringBuilder fullReply = new StringBuilder();

        List<String> sourceCitations = chunks.stream()
                .map(c -> String.format("[%s | Section: %s%s]",
                        c.documentTitle,
                        c.sectionTitle,
                        c.rerankerScore > 0 ? String.format(" | Score: %.3f", c.rerankerScore) : ""))
                .toList();

        try {
            emitter.send(SseEmitter.event().name("sources").data(sourceCitations));
        } catch (IOException e) {
            log.warn("[ChatService] Client closed connection before sources sent");
        }

        // 6. Asynchronous ConverseStream
        ConverseStreamResponseHandler handler = ConverseStreamResponseHandler.builder()
                .subscriber(ConverseStreamResponseHandler.Visitor.builder()
                        .onContentBlockDelta(event -> {
                            String delta = event.delta().text();
                            if (delta != null) {
                                fullReply.append(delta);
                                try {
                                    emitter.send(SseEmitter.event().name("token").data(delta));
                                } catch (Exception ex) {
                                    log.debug("[ChatService] Client disconnected during SSE delivery");
                                }
                            }
                        })
                        .build())
                .onError(err -> {
                    log.error("[ChatService] Bedrock ConverseStream failed: {}", err.getMessage());
                    emitter.completeWithError(err);
                })
                .onComplete(() -> {
                    String finalAnswer = fullReply.toString();
                    if (!finalAnswer.isBlank()) {
                        saveToCache(cacheKey, finalAnswer, 86400);
                        saveConversationTurn(historyKey, userQuery, finalAnswer);
                    }
                    try {
                        emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                        emitter.complete();
                    } catch (Exception ex) {
                        log.debug("[ChatService] Error finalizing SSE: {}", ex.getMessage());
                    }
                })
                .build();

        asyncClient.converseStream(streamRequest, handler);
    }

    public ChatResponse askChat(
            String tenantId,
            List<String> userRoles,
            ChatRequest request
    ) {
        long start = System.currentTimeMillis();
        String sessionId = request.getEffectiveSessionId();
        String userQuery = request.query();
        int topK = request.getEffectiveTopK();
        boolean shouldRerank = request.isRerankRequested(rerankerEnabled);

        if (!checkRateLimit(sessionId, 15, 60)) {
            return new ChatResponse("[Rate Limit Exceeded] Too many requests.", false, 0, List.of());
        }

        String cacheKey = buildSecureCacheKey(tenantId, userRoles, userQuery, shouldRerank);
        String cachedAnswer = getFromCache(cacheKey);

        if (cachedAnswer != null) {
            log.info("[DocLab Cache Hit ({}ms)]: {}", System.currentTimeMillis() - start, cachedAnswer);
            return new ChatResponse(cachedAnswer, true, System.currentTimeMillis() - start, List.of());
        }

        HybridRetrievalService.AuthContext auth = new HybridRetrievalService.AuthContext(
                tenantId != null ? tenantId : "engineering-dept",
                userRoles != null && !userRoles.isEmpty() ? userRoles : List.of("ENGINEERING"),
                "INTERNAL"
        );
        List<HybridRetrievalService.ScoredChunk> chunks = retrieveAndRerank(userQuery, topK, shouldRerank, auth);

        // Strict Guardrail: Prevent hallucination when zero authorized chunks are retrieved
        if (chunks.isEmpty()) {
            String noAccessMsg = "No authorized architectural or incident documentation found matching your query and role permissions.";
            long elapsed = System.currentTimeMillis() - start;
            log.info("[DocLab Guardrail ({}ms)]: Blocked query due to 0 authorized chunks.", elapsed);
            return new ChatResponse(noAccessMsg, false, elapsed, List.of());
        }

        String effectiveTenant = (tenantId != null && !tenantId.isBlank()) ? tenantId : "default";
        String augmentedPrompt = buildAugmentedPrompt(userQuery, chunks);
        String historyKey = "session:" + effectiveTenant + ":" + sessionId + ":history";
        List<Message> messages = buildConverseMessages(historyKey, augmentedPrompt);

        SystemContentBlock systemPrompt = SystemContentBlock.fromText(
                "You are DocLab, an enterprise architecture and knowledge intelligence assistant. " +
                        "Provide direct, concise, technically rigorous answers citing section headers."
        );

        ConverseRequest converseRequest = ConverseRequest.builder()
                .modelId(modelId)
                .system(systemPrompt)
                .messages(messages)
                .inferenceConfig(InferenceConfiguration.builder()
                        .maxTokens(512)
                        .temperature(0.1f)
                        .build())
                .build();

        ConverseResponse response = bedrockClient.converse(converseRequest);
        String assistantReply = response.output().message().content().get(0).text();

        saveToCache(cacheKey, assistantReply, 86400);
        saveConversationTurn(historyKey, userQuery, assistantReply);

        List<String> sources = chunks.stream()
                .map(c -> String.format("[%s | Section: %s%s]",
                        c.documentTitle,
                        c.sectionTitle,
                        c.rerankerScore > 0 ? String.format(" | Score: %.3f", c.rerankerScore) : ""))
                .toList();

        long elapsed = System.currentTimeMillis() - start;
        log.info("[DocLab Reply ({}ms)]: {}", elapsed, assistantReply);
        return new ChatResponse(assistantReply, false, elapsed, sources);
    }

    /**
     * Two-Stage Retrieval Logic:
     * Stage 1: Reciprocal Rank Fusion (pgvector Cosine + BM25 tsvector) fetches candidate pool.
     * Stage 2: Cross-Encoder Reranking via SageMaker (bge-reranker-base) scores candidates down to topK.
     */
    private List<HybridRetrievalService.ScoredChunk> retrieveAndRerank(
            String query,
            int topK,
            boolean enableRerank,
            HybridRetrievalService.AuthContext auth
    ) {
        try {
            boolean shouldRerank = enableRerank && rerankerEnabled;
            int candidatesToFetch = shouldRerank ? Math.max(candidateCount, topK * 3) : topK;

            // Stage 1: Broad Candidate Recall via RRF
            List<HybridRetrievalService.ScoredChunk> candidates = retrievalService.retrieve(query, candidatesToFetch, auth);
            if (candidates.isEmpty()) {
                return Collections.emptyList();
            }

            // Stage 2: Precision Cross-Encoder Reranking
            if (shouldRerank && candidates.size() > 1) {
                return rerankerService.rerankChunks(query, candidates, topK);
            } else {
                return candidates.subList(0, Math.min(topK, candidates.size()));
            }
        } catch (Exception e) {
            log.warn("[ChatService] Retrieval pipeline error: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Retrieval Benchmark Comparison:
     * Evaluates Pure Dense vs Pure Sparse vs Hybrid RRF vs Two-Stage Cross-Encoder Reranking.
     */
    public Map<String, Object> benchmarkRetrieval(String query, String tenantId, List<String> userRoles, int topK) {
        HybridRetrievalService.AuthContext auth = new HybridRetrievalService.AuthContext(
                tenantId != null ? tenantId : "engineering-dept",
                userRoles != null && !userRoles.isEmpty() ? userRoles : List.of("ENGINEERING"),
                "INTERNAL"
        );

        Map<String, Object> benchmark = new HashMap<>();
        benchmark.put("query", query);
        benchmark.put("topK", topK);

        // 1. Pure Dense
        long startDense = System.currentTimeMillis();
        List<HybridRetrievalService.ScoredChunk> denseChunks;
        try {
            denseChunks = retrievalService.retrieveDense(query, topK, auth);
        } catch (Exception e) {
            denseChunks = List.of();
        }
        long denseDuration = System.currentTimeMillis() - startDense;

        // 2. Pure Sparse
        long startSparse = System.currentTimeMillis();
        List<HybridRetrievalService.ScoredChunk> sparseChunks;
        try {
            sparseChunks = retrievalService.retrieveSparse(query, topK, auth);
        } catch (Exception e) {
            sparseChunks = List.of();
        }
        long sparseDuration = System.currentTimeMillis() - startSparse;

        // 3. Hybrid RRF
        long startRrf = System.currentTimeMillis();
        List<HybridRetrievalService.ScoredChunk> rrfChunks;
        try {
            rrfChunks = retrievalService.retrieve(query, topK, auth);
        } catch (Exception e) {
            rrfChunks = List.of();
        }
        long rrfDuration = System.currentTimeMillis() - startRrf;

        // 4. Two-Stage RRF + SageMaker Cross-Encoder Reranking
        long startTwoStage = System.currentTimeMillis();
        List<HybridRetrievalService.ScoredChunk> rerankedChunks;
        try {
            List<HybridRetrievalService.ScoredChunk> candidates = retrievalService.retrieve(query, candidateCount, auth);
            rerankedChunks = rerankerService.rerankChunks(query, candidates, topK);
        } catch (Exception e) {
            rerankedChunks = List.of();
        }
        long twoStageDuration = System.currentTimeMillis() - startTwoStage;

        benchmark.put("denseVector", Map.of(
                "latencyMs", denseDuration,
                "count", denseChunks.size(),
                "chunks", formatChunksForBenchmark(denseChunks)
        ));
        benchmark.put("sparseBM25", Map.of(
                "latencyMs", sparseDuration,
                "count", sparseChunks.size(),
                "chunks", formatChunksForBenchmark(sparseChunks)
        ));
        benchmark.put("hybridRRF", Map.of(
                "latencyMs", rrfDuration,
                "count", rrfChunks.size(),
                "chunks", formatChunksForBenchmark(rrfChunks)
        ));
        benchmark.put("twoStageReranker", Map.of(
                "latencyMs", twoStageDuration,
                "count", rerankedChunks.size(),
                "chunks", formatChunksForBenchmark(rerankedChunks)
        ));

        return benchmark;
    }

    private List<Map<String, Object>> formatChunksForBenchmark(List<HybridRetrievalService.ScoredChunk> chunks) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (HybridRetrievalService.ScoredChunk c : chunks) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("documentTitle", c.documentTitle);
            map.put("sectionTitle", c.sectionTitle);
            map.put("rrfScore", c.rrfScore);
            map.put("rerankerScore", c.rerankerScore);
            map.put("denseScore", c.denseRankScore);
            map.put("sparseScore", c.sparseRankScore);
            map.put("snippet", c.content.length() > 120 ? c.content.substring(0, 120) + "..." : c.content);
            list.add(map);
        }
        return list;
    }

    private String buildAugmentedPrompt(String userQuery, List<HybridRetrievalService.ScoredChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        if (!chunks.isEmpty()) {
            sb.append("Use ONLY the following architectural and incident documentation to answer the question. ");
            sb.append("Always cite the exact Document Title and Section Name when referencing facts.\n\n");
            sb.append("--- DOCUMENTATION CONTEXT ---\n");
            for (HybridRetrievalService.ScoredChunk c : chunks) {
                sb.append(String.format("Source: [%s | Section: %s%s]\nContent: %s\n\n",
                        c.documentTitle,
                        c.sectionTitle,
                        c.rerankerScore > 0 ? String.format(" | Relevance Score: %.4f", c.rerankerScore) : "",
                        c.content));
            }
            sb.append("--- END OF CONTEXT ---\n\n");
        }
        sb.append("User Question: ").append(userQuery);
        return sb.toString();
    }

    private List<Message> buildConverseMessages(String historyKey, String augmentedPrompt) {
        List<Message> messages = new ArrayList<>();
        List<String> rawHistory = getConversationHistory(historyKey);

        for (String turn : rawHistory) {
            String[] parts = turn.split(":::", 2);
            if (parts.length == 2) {
                ConversationRole role = parts[0].equals("USER") ? ConversationRole.USER : ConversationRole.ASSISTANT;
                messages.add(Message.builder()
                        .role(role)
                        .content(ContentBlock.fromText(parts[1]))
                        .build());
            }
        }

        messages.add(Message.builder()
                .role(ConversationRole.USER)
                .content(ContentBlock.fromText(augmentedPrompt))
                .build());

        return messages;
    }

    private boolean checkRateLimit(String key, int maxRequests, int windowSeconds) {
        String rateKey = "rate:" + key;
        try (Jedis jedis = jedisPool.getResource()) {
            long current = jedis.incr(rateKey);
            if (current == 1) {
                jedis.expire(rateKey, windowSeconds);
            }
            return current <= maxRequests;
        } catch (Exception e) {
            log.warn("[Redis Warning] Rate limiter bypassed due to connection issue: {}", e.getMessage());
            return true;
        }
    }

    private String getFromCache(String cacheKey) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.get(cacheKey);
        } catch (Exception e) {
            log.warn("[Redis Warning] Cache read bypassed: {}", e.getMessage());
            return null;
        }
    }

    private void saveToCache(String cacheKey, String value, int ttlSeconds) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.setex(cacheKey, ttlSeconds, value);
        } catch (Exception e) {
            log.warn("[Redis Warning] Cache write failed: {}", e.getMessage());
        }
    }

    private List<String> getConversationHistory(String historyKey) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.lrange(historyKey, 0, -1);
        } catch (Exception e) {
            log.warn("[Redis Warning] Conversation history read failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private void saveConversationTurn(String historyKey, String userQuery, String assistantReply) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.rpush(historyKey, "USER:::" + userQuery);
            jedis.rpush(historyKey, "ASSISTANT:::" + assistantReply);

            long totalTurns = jedis.llen(historyKey);
            if (totalTurns > MAX_HISTORY_TURNS) {
                jedis.ltrim(historyKey, totalTurns - MAX_HISTORY_TURNS, -1);
            }
            jedis.expire(historyKey, 3600);
        } catch (Exception e) {
            log.warn("[Redis Warning] Conversation history write failed: {}", e.getMessage());
        }
    }

    private String hashQuery(String query) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(query.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return Integer.toHexString(query.trim().toLowerCase().hashCode());
        }
    }

    private String buildSecureCacheKey(String tenantId, List<String> userRoles, String userQuery, boolean rerankEnabled) {
        String tenant = (tenantId != null && !tenantId.isBlank()) ? tenantId : "default";
        List<String> sortedRoles = (userRoles != null && !userRoles.isEmpty())
                ? new ArrayList<>(userRoles)
                : new ArrayList<>(List.of("ENGINEERING"));
        Collections.sort(sortedRoles);
        String rolesKey = String.join(",", sortedRoles);
        String queryHash = hashQuery(userQuery);
        String rerankTag = rerankEnabled ? "rerank" : "norerank";
        return String.format("cache:ans:%s:%s:%s:%s", tenant, hashQuery(rolesKey), rerankTag, queryHash);
    }
}
