package com.aws.lab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public class BedrockEmbeddingService {

    private static final String EMBEDDING_MODEL_ID = "amazon.titan-embed-text-v2:0";
    private final BedrockRuntimeClient client;
    private final ObjectMapper mapper;

    public BedrockEmbeddingService(Region region) {
        this.client = BedrockRuntimeClient.builder()
                .region(region)
                .build();
        this.mapper = new ObjectMapper();
    }

    public float[] generateEmbedding(String text) {
        try {
            Map<String, Object> payload = Map.of(
                    "inputText", text,
                    "dimensions", 1024,
                    "normalize", true
            );

            String requestBody = mapper.writeValueAsString(payload);

            InvokeModelRequest request = InvokeModelRequest.builder()
                    .modelId(EMBEDDING_MODEL_ID)
                    .contentType("application/json")
                    .accept("application/json")
                    .body(SdkBytes.fromString(requestBody, StandardCharsets.UTF_8))
                    .build();

            InvokeModelResponse response = client.invokeModel(request);
            String responseJson = response.body().asString(StandardCharsets.UTF_8);

            JsonNode root = mapper.readTree(responseJson);
            JsonNode embeddingNode = root.path("embedding");

            float[] vector = new float[embeddingNode.size()];
            for (int i = 0; i < embeddingNode.size(); i++) {
                vector[i] = (float) embeddingNode.get(i).asDouble();
            }

            return vector;
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate embedding from Bedrock: " + e.getMessage(), e);
        }
    }
}