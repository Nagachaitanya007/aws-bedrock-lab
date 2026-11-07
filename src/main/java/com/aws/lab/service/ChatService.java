package com.aws.lab.service;

import com.aws.lab.HybridRetrievalService;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final int MAX_HISTORY_TURNS = 6;

    private final BedrockRuntimeClient bedrockClient;
    private final BedrockRuntimeAsyncClient asyncClient;
    private final HybridRetrievalService retrievalService;
    private final JedisPool jedisPool;

    @Value("${doclab.bedrock.model-id:amazon.nova-micro-v1:0}")
    private String modelId;

    public ChatService(
            BedrockRuntimeClient bedrockClient,
            BedrockRuntimeAsyncClient asyncClient,
            HybridRetrievalService retrievalService,
            JedisPool jedisPool
    ) {
        this.bedrockClient = bedrockClient;
        this.asyncClient = asyncClient;
        this.retrievalService = retrievalService;
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
        String queryHash = hashQuery(userQuery);
        String cacheKey = buildSecureCacheKey(tenantId, userRoles, userQuery);
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

        // 3. Hybrid RRF Retrieval with RBAC Governance
        List<HybridRetrievalService.ScoredChunk> chunks = Collections.emptyList();
        try {
            HybridRetrievalService.AuthContext auth = new HybridRetrievalService.AuthContext(
                    tenantId != null ? tenantId : "engineering-dept",
                    userRoles != null && !userRoles.isEmpty() ? userRoles : List.of("ENGINEERING"),
                    "INTERNAL"
            );
            chunks = retrievalService.retrieve(userQuery, topK, auth);
        } catch (Exception e) {
            log.warn("[ChatService] Retrieval error: {}", e.getMessage());
        }

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

        // Send sources header event before token generation
        List<String> sourceCitations = chunks.stream()
                .map(c -> String.format("[%s | Section: %s]", c.documentTitle, c.sectionTitle))
                .toList();

        try {
            emitter.send(SseEmitter.event().name("sources").data(sourceCitations));
        } catch (IOException e) {
            log.warn("[ChatService] Client closed connection before sources sent");
        }

        // 5. Asynchronous ConverseStream
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

        if (!checkRateLimit(sessionId, 15, 60)) {
            return new ChatResponse("[Rate Limit Exceeded] Too many requests.", false, 0, List.of());
        }

        String queryHash = hashQuery(userQuery);
        String cacheKey = buildSecureCacheKey(tenantId, userRoles, userQuery);
        String cachedAnswer = getFromCache(cacheKey);

        if (cachedAnswer != null) {
            log.info("[DocLab Cache Hit ({}ms)]: {}", System.currentTimeMillis() - start, cachedAnswer);
            return new ChatResponse(cachedAnswer, true, System.currentTimeMillis() - start, List.of());
        }

        List<HybridRetrievalService.ScoredChunk> chunks = Collections.emptyList();
        try {
            HybridRetrievalService.AuthContext auth = new HybridRetrievalService.AuthContext(
                    tenantId != null ? tenantId : "engineering-dept",
                    userRoles != null && !userRoles.isEmpty() ? userRoles : List.of("ENGINEERING"),
                    "INTERNAL"
            );
            chunks = retrievalService.retrieve(userQuery, topK, auth);
        } catch (Exception e) {
            log.warn("[ChatService] Retrieval error: {}", e.getMessage());
        }

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
                .map(c -> String.format("[%s | Section: %s]", c.documentTitle, c.sectionTitle))
                .toList();

        long elapsed = System.currentTimeMillis() - start;
        log.info("[DocLab Reply ({}ms)]: {}", elapsed, assistantReply);
        return new ChatResponse(assistantReply, false, elapsed, sources);
    }

    private String buildAugmentedPrompt(String userQuery, List<HybridRetrievalService.ScoredChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        if (!chunks.isEmpty()) {
            sb.append("Use ONLY the following architectural and incident documentation to answer the question. ");
            sb.append("Always cite the exact Document Title and Section Name when referencing facts.\n\n");
            sb.append("--- DOCUMENTATION CONTEXT ---\n");
            for (HybridRetrievalService.ScoredChunk c : chunks) {
                sb.append(String.format("Source: [%s | Section: %s]\nContent: %s\n\n",
                        c.documentTitle, c.sectionTitle, c.content));
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
            return true; // Fail-open
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

    private String buildSecureCacheKey(String tenantId, List<String> userRoles, String userQuery) {
        String tenant = (tenantId != null && !tenantId.isBlank()) ? tenantId : "default";
        List<String> sortedRoles = (userRoles != null && !userRoles.isEmpty())
                ? new ArrayList<>(userRoles)
                : new ArrayList<>(List.of("ENGINEERING"));
        Collections.sort(sortedRoles);
        String rolesKey = String.join(",", sortedRoles);
        String queryHash = hashQuery(userQuery);
        return String.format("cache:ans:%s:%s:%s", tenant, hashQuery(rolesKey), queryHash);
    }
}
