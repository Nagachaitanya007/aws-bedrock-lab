package com.aws.lab;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class BedrockChatSession {

    public static void main(String[] args) {
        Region region = Region.US_EAST_1;
        String modelId = "amazon.nova-micro-v1:0";

        // This list is the "memory". It stores the chronological conversation.
        List<Message> conversationHistory = new ArrayList<>();

        System.out.println("====================================================");
        System.out.println(" Bedrock Interactive Session (amazon.nova-micro-v1:0)");
        System.out.println(" Type your message and hit Enter. Type 'exit' to quit.");
        System.out.println("====================================================\n");

        try (BedrockRuntimeClient client = BedrockRuntimeClient.builder()
                .region(region)
                .build();
             Scanner scanner = new Scanner(System.in)) {

            while (scanner.hasNextLine())  {
                System.out.print("\nYou > ");
                String userInput = scanner.nextLine().trim();

                if (userInput.equalsIgnoreCase("exit")) {
                    System.out.println("Exiting chat session. Goodbye!");
                    break;
                }

                if (userInput.isEmpty()) {
                    continue;
                }

                // 1. Wrap user input into a Message and add to our history
                Message userMessage = Message.builder()
                        .role(ConversationRole.USER)
                        .content(ContentBlock.fromText(userInput))
                        .build();
                conversationHistory.add(userMessage);

                // 2. Build Converse request with the FULL conversation history
                ConverseRequest request = ConverseRequest.builder()
                        .modelId(modelId)
                        .messages(conversationHistory)
                        .build();

                // 3. Invoke Bedrock
                ConverseResponse response = client.converse(request);

                // 4. Extract reply text
                String assistantReply = response.output().message().content().get(0).text();
                System.out.println("\nBedrock > " + assistantReply);

                // 5. Append assistant's reply to history so context continues next turn
                Message assistantMessage = Message.builder()
                        .role(ConversationRole.ASSISTANT)
                        .content(ContentBlock.fromText(assistantReply))
                        .build();
                conversationHistory.add(assistantMessage);

                // 6. Inspect context growth
                System.out.println("\n[Context Metrics: "
                        + conversationHistory.size() + " total messages stored | "
                        + "Last turn tokens -> In: " + response.usage().inputTokens()
                        + ", Out: " + response.usage().outputTokens() + "]");
            }

        } catch (Exception e) {
            System.err.println("Session error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}