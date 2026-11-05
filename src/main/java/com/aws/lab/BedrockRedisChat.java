package com.aws.lab;

import redis.clients.jedis.Jedis;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class BedrockRedisChat {

    private static final String MODEL_ID = "amazon.nova-micro-v1:0";
    private static final String REDIS_HOST = "127.0.0.1";
    private static final int REDIS_PORT = 6379;
    private static final String SESSION_KEY = "chat:session:default";
    private static final int MAX_HISTORY_TURNS = 6; // Keeps last 3 user + 3 assistant turns

    // Candidate domain passages to simulate RAG retrieval
    private static final List<String> KNOWLEDGE_BASE = List.of(
            "Amazon SQS provides message queuing with dead-letter queue (DLQ) support for decoupling distributed microservices.",
            "Amazon ElastiCache Redis is used as an in-memory session cache to maintain stateful conversation context.",
            "Amazon SageMaker real-time endpoints run Hugging Face cross-encoder models on dedicated EC2 compute instances for semantic reranking.",
            "Amazon Bedrock provides managed foundation models accessible via the Converse API for low-latency text generation."
    );

    public static void main(String[] args) {
        System.out.println(">>> Initializing Bedrock Redis + SageMaker Chat...");

        // 1. Redis Client via localhost tunnel
        Jedis jedis = new Jedis(REDIS_HOST, REDIS_PORT);
        try {
            String ping = jedis.ping();
            System.out.println("[Redis Health Check] PING -> " + ping);
        } catch (Exception e) {
            System.err.println("[Error] Cannot reach Redis on 127.0.0.1:6379. Make sure run-app.ps1 is active.");
            return;
        }

        // 2. Bedrock & SageMaker Clients
        BedrockRuntimeClient bedrockClient = BedrockRuntimeClient.builder()
                .region(Region.US_EAST_1)
                .build();

        SageMakerRerankerService reranker = new SageMakerRerankerService("bge-reranker-endpoint", Region.US_EAST_1);

        Scanner scanner = new Scanner(System.in);
        System.out.println("\n=== Architecture Chatbot Ready (Type 'exit' to quit, 'clear' to reset memory) ===");

        while (true) {
            System.out.print("\nYou > ");
            String userInput = scanner.nextLine().trim();

            if (userInput.equalsIgnoreCase("exit")) {
                System.out.println("Exiting session...");
                break;
            }

            if (userInput.equalsIgnoreCase("clear")) {
                jedis.del(SESSION_KEY);
                System.out.println("[Redis] Session history cleared.");
                continue;
            }

            if (userInput.isEmpty()) {
                continue;
            }

            // 3. SageMaker Cross-Encoder Reranking
            System.out.println("[SageMaker] Scoring candidate passages...");
            List<SageMakerRerankerService.ScoredDocument> rankedDocs = reranker.rerank(userInput, KNOWLEDGE_BASE);

            String topContext = "";
            if (!rankedDocs.isEmpty()) {
                SageMakerRerankerService.ScoredDocument top = rankedDocs.get(0);
                topContext = top.text();
                System.out.printf("[SageMaker Reranker] Top Match (Score: %.4f): \"%s\"\n", top.score(), topContext);
            }

            // 4. Build Context-Augmented Prompt
            String augmentedMessage = String.format("Context:\n%s\n\nQuestion:\n%s", topContext, userInput);

            // 5. Save User Turn to Redis
            jedis.rpush(SESSION_KEY, "user:" + augmentedMessage);

            // Trim Redis history to keep within sliding window
            long totalItems = jedis.llen(SESSION_KEY);
            if (totalItems > MAX_HISTORY_TURNS) {
                jedis.ltrim(SESSION_KEY, totalItems - MAX_HISTORY_TURNS, -1);
            }

            // 6. Build Converse API History from Redis
            List<String> rawHistory = jedis.lrange(SESSION_KEY, 0, -1);
            List<Message> conversationMessages = new ArrayList<>();

            for (String entry : rawHistory) {
                if (entry.startsWith("user:")) {
                    conversationMessages.add(Message.builder()
                            .role(ConversationRole.USER)
                            .content(ContentBlock.fromText(entry.substring(5)))
                            .build());
                } else if (entry.startsWith("assistant:")) {
                    conversationMessages.add(Message.builder()
                            .role(ConversationRole.ASSISTANT)
                            .content(ContentBlock.fromText(entry.substring(10)))
                            .build());
                }
            }

            // 7. Invoke Amazon Bedrock
            System.out.print("Bedrock > ");
            try {
                ConverseRequest request = ConverseRequest.builder()
                        .modelId(MODEL_ID)
                        .messages(conversationMessages)
                        .system(SystemContentBlock.fromText("You are an enterprise cloud solutions architect. Answer concisely using provided context."))
                        .inferenceConfig(InferenceConfiguration.builder()
                                .maxTokens(512)
                                .temperature(0.3f)
                                .build())
                        .build();

                ConverseResponse response = bedrockClient.converse(request);
                String assistantReply = response.output().message().content().get(0).text();
                System.out.println(assistantReply);

                // Save Assistant Turn to Redis
                jedis.rpush(SESSION_KEY, "assistant:" + assistantReply);

            } catch (Exception e) {
                System.err.println("\n[Bedrock Error] " + e.getMessage());
            }
        }

        jedis.close();
        bedrockClient.close();
    }
}