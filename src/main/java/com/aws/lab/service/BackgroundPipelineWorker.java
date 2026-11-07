package com.aws.lab.service;

import com.aws.lab.SemanticIndexerWorker;
import com.aws.lab.UniversalParserWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class BackgroundPipelineWorker {

    private static final Logger log = LoggerFactory.getLogger(BackgroundPipelineWorker.class);

    private final S3Client s3Client;
    private final SqsClient sqsClient;
    private final BedrockRuntimeClient bedrockRuntimeClient;

    @Value("${doclab.workers.auto-enabled:true}")
    private volatile boolean autoEnabled;

    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private final AtomicLong totalParsedCount = new AtomicLong(0);
    private final AtomicLong totalIndexedCount = new AtomicLong(0);

    public BackgroundPipelineWorker(S3Client s3Client, SqsClient sqsClient, BedrockRuntimeClient bedrockRuntimeClient) {
        this.s3Client = s3Client;
        this.sqsClient = sqsClient;
        this.bedrockRuntimeClient = bedrockRuntimeClient;
    }

    @Scheduled(fixedDelayString = "${doclab.workers.poll-interval-ms:3000}", initialDelay = 5000)
    public void runScheduledCycle() {
        if (!autoEnabled) {
            return;
        }

        drainQueues("AUTO");
    }

    /**
     * Drains both the Universal Parser queue and the Semantic Indexer queue.
     * Can be invoked automatically by the scheduler or on-demand via REST endpoint.
     */
    public Map<String, Object> drainQueues(String triggerSource) {
        Map<String, Object> result = new HashMap<>();

        if (!isProcessing.compareAndSet(false, true)) {
            result.put("status", "BUSY");
            result.put("message", "A pipeline drain cycle is already in progress.");
            return result;
        }

        try {
            int parsed = UniversalParserWorker.drainQueue(s3Client, sqsClient, 1);
            if (parsed > 0) {
                totalParsedCount.addAndGet(parsed);
                log.info("[BackgroundPipelineWorker][{}] Automatically parsed {} document(s) from SQS.", triggerSource, parsed);
            }

            int indexed = SemanticIndexerWorker.drainQueue(s3Client, sqsClient, bedrockRuntimeClient, 1);
            if (indexed > 0) {
                totalIndexedCount.addAndGet(indexed);
                log.info("[BackgroundPipelineWorker][{}] Automatically indexed and activated {} document(s) in pgvector.", triggerSource, indexed);
            }

            result.put("status", "SUCCESS");
            result.put("triggerSource", triggerSource);
            result.put("parsedCount", parsed);
            result.put("indexedCount", indexed);
            result.put("totalParsedSoFar", totalParsedCount.get());
            result.put("totalIndexedSoFar", totalIndexedCount.get());
            return result;
        } catch (Exception e) {
            log.error("[BackgroundPipelineWorker][{}] Error during pipeline drain: {}", triggerSource, e.getMessage(), e);
            result.put("status", "ERROR");
            result.put("error", e.getMessage());
            return result;
        } finally {
            isProcessing.set(false);
        }
    }

    public boolean isAutoEnabled() {
        return autoEnabled;
    }

    public void setAutoEnabled(boolean autoEnabled) {
        this.autoEnabled = autoEnabled;
        log.info("[BackgroundPipelineWorker] Automatic background polling set to: {}", autoEnabled);
    }

    public Map<String, Object> getWorkerStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("autoEnabled", autoEnabled);
        status.put("isProcessing", isProcessing.get());
        status.put("totalParsedCount", totalParsedCount.get());
        status.put("totalIndexedCount", totalIndexedCount.get());
        return status;
    }
}
