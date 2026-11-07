package com.aws.lab;

import software.amazon.awssdk.regions.Region;

import java.util.List;

public class RagBenchmarkEvaluator {

    private static final Region REGION = Region.US_EAST_1;
    private static final String RERANKER_ENDPOINT = System.getenv().getOrDefault("SAGEMAKER_RERANKER_ENDPOINT", "bge-reranker-endpoint");

    public static final String RESET = "\u001B[0m";
    public static final String CYAN = "\u001B[36m";
    public static final String GREEN = "\u001B[32m";
    public static final String YELLOW = "\u001B[33m";
    public static final String BOLD = "\u001B[1m";

    public static void main(String[] args) {
        System.out.println(CYAN + BOLD + "================================================================================" + RESET);
        System.out.println(CYAN + BOLD + "           DOCLAB RETRIEVAL BENCHMARK & TWO-STAGE RERANKING EVALUATION          " + RESET);
        System.out.println(CYAN + BOLD + "================================================================================" + RESET);

        HybridRetrievalService retrievalService = new HybridRetrievalService(REGION);
        SageMakerRerankerService rerankerService = new SageMakerRerankerService(RERANKER_ENDPOINT, REGION);
        HybridRetrievalService.AuthContext auth = HybridRetrievalService.AuthContext.defaultEngineering();

        List<String> testQueries = List.of(
                "What is the scaling policy and database for checkout-service?",
                "Why did we decide to adopt Kafka in ADR-025?",
                "What are the consequences listed in ADR-025?"
        );

        for (int qIdx = 0; qIdx < testQueries.size(); qIdx++) {
            String query = testQueries.get(qIdx);
            System.out.println("\n" + YELLOW + BOLD + String.format("[TEST QUERY %d/%d]: \"%s\"", qIdx + 1, testQueries.size(), query) + RESET);

            try {
                // 1. Pure Dense
                long t0 = System.currentTimeMillis();
                List<HybridRetrievalService.ScoredChunk> dense = retrievalService.retrieveDense(query, 3, auth);
                long denseMs = System.currentTimeMillis() - t0;

                // 2. Pure Sparse
                long t1 = System.currentTimeMillis();
                List<HybridRetrievalService.ScoredChunk> sparse = retrievalService.retrieveSparse(query, 3, auth);
                long sparseMs = System.currentTimeMillis() - t1;

                // 3. Hybrid RRF (Top 10 candidates)
                long t2 = System.currentTimeMillis();
                List<HybridRetrievalService.ScoredChunk> candidates = retrievalService.retrieve(query, 10, auth);
                long rrfMs = System.currentTimeMillis() - t2;

                // 4. Two-Stage RRF + SageMaker Cross-Encoder Reranker (Top 3)
                long t3 = System.currentTimeMillis();
                List<HybridRetrievalService.ScoredChunk> reranked = rerankerService.rerankChunks(query, candidates, 3);
                long rerankMs = System.currentTimeMillis() - t3;

                // Print Summary Table
                System.out.println(BOLD + "--------------------------------------------------------------------------------" + RESET);
                System.out.printf("%-20s | %-12s | %-40s%n", "Retrieval Mode", "Latency", "Rank #1 Selection & Score");
                System.out.println(BOLD + "--------------------------------------------------------------------------------" + RESET);

                if (!dense.isEmpty()) {
                    System.out.printf("%-20s | %-10dms | [%s] Sim: %.4f%n",
                            "1. Pure Dense", denseMs, dense.get(0).documentTitle, dense.get(0).denseRankScore);
                } else {
                    System.out.printf("%-20s | %-10dms | (0 results)%n", "1. Pure Dense", denseMs);
                }

                if (!sparse.isEmpty()) {
                    System.out.printf("%-20s | %-10dms | [%s] BM25: %.4f%n",
                            "2. Pure Sparse", sparseMs, sparse.get(0).documentTitle, sparse.get(0).sparseRankScore);
                } else {
                    System.out.printf("%-20s | %-10dms | (0 results)%n", "2. Pure Sparse", sparseMs);
                }

                if (!candidates.isEmpty()) {
                    System.out.printf("%-20s | %-10dms | [%s] RRF: %.4f%n",
                            "3. Hybrid RRF", rrfMs, candidates.get(0).documentTitle, candidates.get(0).rrfScore);
                } else {
                    System.out.printf("%-20s | %-10dms | (0 results)%n", "3. Hybrid RRF", rrfMs);
                }

                if (!reranked.isEmpty()) {
                    System.out.printf(GREEN + BOLD + "%-20s | %-10dms | [%s] BGE Score: %.4f%n" + RESET,
                            "4. RRF + Reranker", (rrfMs + rerankMs), reranked.get(0).documentTitle, reranked.get(0).rerankerScore);
                } else {
                    System.out.printf("%-20s | %-10dms | (0 results)%n", "4. RRF + Reranker", (rrfMs + rerankMs));
                }

                System.out.println(BOLD + "--------------------------------------------------------------------------------" + RESET);

                if (!reranked.isEmpty()) {
                    System.out.println(GREEN + ">>> Top Reranked Chunk Content:" + RESET);
                    HybridRetrievalService.ScoredChunk top = reranked.get(0);
                    System.out.println("    Source: " + top.documentTitle + " | Section: " + top.sectionTitle);
                    String snippet = top.content.replaceAll("\\s+", " ");
                    if (snippet.length() > 180) snippet = snippet.substring(0, 180) + "...";
                    System.out.println("    Snippet: \"" + snippet + "\"");
                }

            } catch (Exception e) {
                System.err.println("Benchmark error for query: " + e.getMessage());
                e.printStackTrace();
            }
        }

        System.out.println("\n" + CYAN + BOLD + "================================================================================" + RESET);
        System.out.println(CYAN + BOLD + "                   BENCHMARK EVALUATION COMPLETED SUCCESSFULLY                  " + RESET);
        System.out.println(CYAN + BOLD + "================================================================================" + RESET);
    }
}
