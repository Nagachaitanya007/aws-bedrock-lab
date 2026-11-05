package com.aws.lab;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamResponseHandler;
import software.amazon.awssdk.services.bedrockruntime.model.Message;

public class BedrockStreamTest {
    public static void main(String[] args) {
        Region region = Region.US_EAST_1;
        String modelId = "amazon.nova-micro-v1:0";

        System.out.println("Connecting to Bedrock Async Client for streaming...\n");

        try (BedrockRuntimeAsyncClient asyncClient = BedrockRuntimeAsyncClient.builder()
                .region(region)
                .build()) {

            Message userMessage = Message.builder()
                    .role(ConversationRole.USER)
                    .content(ContentBlock.fromText("Write a 4-bullet point checklist for designing an idempotent consumer in Kafka."))
                    .build();

            ConverseStreamRequest request = ConverseStreamRequest.builder()
                    .modelId(modelId)
                    .messages(userMessage)
                    .build();

            // Set up stream event handler to print tokens as they arrive
            ConverseStreamResponseHandler handler = ConverseStreamResponseHandler.builder()
                    .subscriber(ConverseStreamResponseHandler.Visitor.builder()
                            .onContentBlockDelta(event -> {
                                System.out.print(event.delta().text());
                                System.out.flush();
                            })
                            .build())
                    .onError(err -> System.err.println("\nStream error: " + err.getMessage()))
                    .onComplete(() -> System.out.println("\n\n--- Stream finished ---"))
                    .build();

            // Wait for completion
            asyncClient.converseStream(request, handler).join();

        } catch (Exception e) {
            System.err.println("Execution failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}