package com.aws.lab;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;

public class BedrockTest {
    public static void main(String[] args) {
        // Bedrock models are widely available in us-east-1
        Region region = Region.US_EAST_1;

        System.out.println("Initializing Bedrock Runtime Client in " + region.id() + "...");

        try (BedrockRuntimeClient client = BedrockRuntimeClient.builder()
                .region(region)
                .build()) {

            // Lightweight, low-cost model for testing connectivity
            String modelId = "amazon.nova-micro-v1:0";

            Message userMessage = Message.builder()
                    .role(ConversationRole.USER)
                    .content(ContentBlock.fromText("In 2 short sentences, why is event streaming critical for microservices?"))
                    .build();

            ConverseRequest request = ConverseRequest.builder()
                    .modelId(modelId)
                    .messages(userMessage)
                    .build();

            System.out.println("Invoking model: " + modelId + "...");
            ConverseResponse response = client.converse(request);

            String reply = response.output().message().content().get(0).text();
            System.out.println("\n--- Model Response ---");
            System.out.println(reply);
            System.out.println("----------------------");
            System.out.println("Token Usage -> Input: "
                    + response.usage().inputTokens()
                    + " | Output: " + response.usage().outputTokens());

        } catch (Exception e) {
            System.err.println("\nExecution failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}