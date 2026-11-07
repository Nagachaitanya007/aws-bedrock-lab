package com.aws.lab.config;

import com.aws.lab.DbConfig;
import com.aws.lab.HybridRetrievalService;
import com.aws.lab.SageMakerRerankerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.time.Duration;

@Configuration
public class DocLabSpringConfig {

    @Value("${doclab.aws.region:us-east-1}")
    private String awsRegion;

    @Value("${doclab.sagemaker.reranker-endpoint:bge-reranker-endpoint}")
    private String rerankerEndpoint;

    @Bean
    public Region awsRegion() {
        return Region.of(awsRegion);
    }

    @Bean
    public BedrockRuntimeClient bedrockRuntimeClient(Region region) {
        return BedrockRuntimeClient.builder()
                .region(region)
                .build();
    }

    @Bean
    public BedrockRuntimeAsyncClient bedrockRuntimeAsyncClient(Region region) {
        return BedrockRuntimeAsyncClient.builder()
                .region(region)
                .build();
    }

    @Bean
    public S3Client s3Client(Region region) {
        return S3Client.builder()
                .region(region)
                .build();
    }

    @Bean
    public SqsClient sqsClient(Region region) {
        return SqsClient.builder()
                .region(region)
                .build();
    }

    @Bean
    public JedisPool jedisPool() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(32);
        poolConfig.setMaxIdle(16);
        poolConfig.setMinIdle(4);
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestWhileIdle(true);
        poolConfig.setMinEvictableIdleDuration(Duration.ofSeconds(60));
        poolConfig.setTimeBetweenEvictionRuns(Duration.ofSeconds(30));

        return new JedisPool(poolConfig, DbConfig.getRedisHost(), DbConfig.getRedisPort(), 5000);
    }

    @Bean
    public HybridRetrievalService hybridRetrievalService(Region region) {
        return new HybridRetrievalService(region);
    }

    @Bean
    public SageMakerRerankerService sageMakerRerankerService(Region region) {
        return new SageMakerRerankerService(rerankerEndpoint, region);
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
