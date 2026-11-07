package com.aws.lab;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

public class DocLabEngine {

    private static final String MODEL_ID = "amazon.nova-micro-v1:0";
    private static final int REDIS_TIMEOUT_MS = 5000;
    private static final int MAX_HISTORY_TURNS = 6; // 3 user + 3 assistant turns

    public static final String RESET = "\u001B[0m";
    public static final String CYAN = "\u001B[36m";
    public static final String GREEN = "\u001B[32m";
    public static final String YELLOW = "\u001B[33m";
    public static final String RED = "\u001B[31m";
    public static final String BOLD = "\u001B[1m";

    private final BedrockRuntimeClient bedrockClient;
    private final HybridRetrievalService retrievalService;
    private final JedisPool jedisPool;

    public DocLabEngine(Region region) {
        this.bedrockClient = BedrockRuntimeClient.builder().region(region).build();
        this.retrievalService = new HybridRetrievalService(region);

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(16);
        poolConfig.setMaxIdle(8);
        poolConfig.setMinIdle(2);
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestWhileIdle(true);
        poolConfig.setMinEvictableIdleDuration(Duration.ofSeconds(60));
        poolConfig.setTimeBetweenEvictionRuns(Duration.ofSeconds(30));

        this.jedisPool = new JedisPool(poolConfig, DbConfig.getRedisHost(), DbConfig.getRedisPort(), REDIS_TIMEOUT_MS);
    }

    public String ask(String sessionId, String userQuery) {
        // 1. Guardrail Rate Limiter (Token bucket via Redis with fail-open fallback)
        if (!checkRateLimit(sessionId, 10, 60)) {
            return "[Rate Limit Exceeded] Too many requests. Please wait a moment.";
        }

        // 2. Exact-Match Answer Cache Check via Redis
        String queryHash = hashQuery(userQuery);
        String cacheKey = "cache:ans:" + queryHash;
        String cachedAnswer = getFromCache(cacheKey);
        if (cachedAnswer != null) {
            return "[Redis Cache Hit - Zero AWS API Invocations]\n" + cachedAnswer;
        }

        // 3. Retrieve top candidates via pgvector hybrid search (Governance active)
        List<HybridRetrievalService.ScoredChunk> chunks = Collections.emptyList();
        try {
            chunks = retrievalService.retrieve(userQuery, 3);
        } catch (Exception e) {
            System.err.println(RED + "[Retrieval Error] " + e.getMessage() + RESET);
        }

        // 4. Build Context Block
        StringBuilder contextBuilder = new StringBuilder();
        if (!chunks.isEmpty()) {
            contextBuilder.append("Use ONLY the following architectural and incident documentation to answer the question. ");
            contextBuilder.append("Always cite the exact Document Title and Section Name when referencing facts.\n\n");
            contextBuilder.append("--- DOCUMENTATION CONTEXT ---\n");
            for (HybridRetrievalService.ScoredChunk c : chunks) {
                contextBuilder.append(String.format("Source: [%s | Section: %s]\nContent: %s\n\n",
                        c.documentTitle, c.sectionTitle, c.content));
            }
            contextBuilder.append("--- END OF CONTEXT ---\n\n");
        }

        // 5. Build Converse Messages with Redis Conversation Memory
        String historyKey = "session:" + sessionId + ":history";
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

        // Append current query with injected context
        String augmentedPrompt = contextBuilder + "User Question: " + userQuery;
        messages.add(Message.builder()
                .role(ConversationRole.USER)
                .content(ContentBlock.fromText(augmentedPrompt))
                .build());

        SystemContentBlock systemPrompt = SystemContentBlock.fromText(
                "You are DocLab, an enterprise architecture and knowledge intelligence assistant. " +
                        "Provide direct, concise, technically rigorous answers citing section headers."
        );

        ConverseRequest request = ConverseRequest.builder()
                .modelId(MODEL_ID)
                .system(systemPrompt)
                .messages(messages)
                .inferenceConfig(InferenceConfiguration.builder()
                        .maxTokens(512)
                        .temperature(0.1f)
                        .build())
                .build();

        ConverseResponse response = bedrockClient.converse(request);
        String assistantReply = response.output().message().content().get(0).text();

        // 6. Cache the synthesized answer in Redis (24-hour TTL)
        saveToCache(cacheKey, assistantReply, 86400);

        // 7. Store Turn in Redis Conversation History
        saveConversationTurn(historyKey, userQuery, assistantReply);

        return assistantReply;
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
            System.err.println(RED + "[Redis Warning] Rate limiter bypassed due to connection issue: " + e.getMessage() + RESET);
            return true; // Fail-open to preserve chat availability
        }
    }

    private String getFromCache(String cacheKey) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.get(cacheKey);
        } catch (Exception e) {
            System.err.println(RED + "[Redis Warning] Cache read bypassed: " + e.getMessage() + RESET);
            return null;
        }
    }

    private void saveToCache(String cacheKey, String value, int ttlSeconds) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.setex(cacheKey, ttlSeconds, value);
        } catch (Exception e) {
            System.err.println(RED + "[Redis Warning] Cache write failed: " + e.getMessage() + RESET);
        }
    }

    private List<String> getConversationHistory(String historyKey) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.lrange(historyKey, 0, -1);
        } catch (Exception e) {
            System.err.println(RED + "[Redis Warning] Conversation history read failed: " + e.getMessage() + RESET);
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
            System.err.println(RED + "[Redis Warning] Conversation history write failed: " + e.getMessage() + RESET);
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

    public static void main(String[] args) {
        System.out.println(CYAN + ">>> Initializing DocLab Interactive Engine..." + RESET);
        DocLabEngine engine = new DocLabEngine(Region.US_EAST_1);

        String testSession = "engineer-session-001";
        Scanner scanner = new Scanner(System.in);

        System.out.println("\n" + BOLD + "==========================================================" + RESET);
        System.out.println(BOLD + " DocLab Intelligence Console " + RESET + "(Type 'exit' to quit)");
        System.out.println(BOLD + "==========================================================" + RESET);

        while (true) {
            System.out.print("\n" + GREEN + "Query > " + RESET);
            if (!scanner.hasNextLine()) break;
            String input = scanner.nextLine().trim();
            if (input.equalsIgnoreCase("exit")) break;
            if (input.isEmpty()) continue;

            long start = System.currentTimeMillis();
            String answer = engine.ask(testSession, input);
            long elapsed = System.currentTimeMillis() - start;

            System.out.println("\n" + YELLOW + "--- Response [" + elapsed + "ms] ---" + RESET);
            System.out.println(answer);
            System.out.println(YELLOW + "------------------------" + RESET);
        }
    }
}