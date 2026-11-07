package com.aws.lab.controller;

import com.aws.lab.dto.ChatRequest;
import com.aws.lab.dto.ChatResponse;
import com.aws.lab.service.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/chat")
@CrossOrigin(origins = "*")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * Server-Sent Events (SSE) streaming endpoint.
     * Emits token deltas in real-time as Amazon Bedrock generates them.
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "engineering-dept") String tenantId,
            @RequestHeader(value = "X-User-Roles", defaultValue = "ENGINEERING") String userRolesHeader,
            @RequestBody ChatRequest request
    ) {
        log.info("[ChatController] SSE stream requested for tenant '{}', query: '{}'", tenantId, request.query());
        List<String> roles = parseRoles(userRolesHeader);

        // 120-second timeout for streaming
        SseEmitter emitter = new SseEmitter(120_000L);

        emitter.onTimeout(() -> {
            log.warn("[ChatController] SSE connection timed out");
            emitter.complete();
        });

        emitter.onError(e -> log.debug("[ChatController] SSE error: {}", e.getMessage()));

        chatService.streamChat(tenantId, roles, request, emitter);

        return emitter;
    }

    /**
     * Synchronous request-response endpoint.
     * Useful for curl / standard HTTP clients not consuming SSE.
     */
    @PostMapping("/ask")
    public ResponseEntity<ChatResponse> askChat(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "engineering-dept") String tenantId,
            @RequestHeader(value = "X-User-Roles", defaultValue = "ENGINEERING") String userRolesHeader,
            @RequestBody ChatRequest request
    ) {
        log.info("[ChatController] Sync ask requested for tenant '{}', query: '{}'", tenantId, request.query());
        List<String> roles = parseRoles(userRolesHeader);
        ChatResponse response = chatService.askChat(tenantId, roles, request);
        return ResponseEntity.ok(response);
    }

    /**
     * Retrieval Benchmark & Comparison Endpoint.
     * Evaluates Pure Dense vs Pure Sparse vs Hybrid RRF vs Two-Stage Cross-Encoder Reranking side-by-side.
     */
    @PostMapping("/benchmark")
    public ResponseEntity<Map<String, Object>> benchmarkRetrieval(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "engineering-dept") String tenantId,
            @RequestHeader(value = "X-User-Roles", defaultValue = "ENGINEERING") String userRolesHeader,
            @RequestBody ChatRequest request
    ) {
        log.info("[ChatController] Benchmark requested for query: '{}'", request.query());
        List<String> roles = parseRoles(userRolesHeader);
        Map<String, Object> result = chatService.benchmarkRetrieval(
                request.query(),
                tenantId,
                roles,
                request.getEffectiveTopK()
        );
        return ResponseEntity.ok(result);
    }

    private List<String> parseRoles(String header) {
        if (header == null || header.isBlank()) {
            return List.of("ENGINEERING");
        }
        return Arrays.stream(header.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
