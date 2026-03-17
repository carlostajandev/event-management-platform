package com.nequi.shared.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;

import java.net.URI;
import java.time.Duration;

@Configuration
public class DynamoDbConfig {

    @Value("${aws.region:us-east-1}")
    private String region;

    // ── Local / Test profiles → LocalStack endpoint ──────────────────────────

    @Bean
    @Profile({"local", "test"})
    public DynamoDbAsyncClient dynamoDbAsyncClientLocal(
            @Value("${aws.dynamodb.endpoint:http://localhost:4566}") String endpoint) {
        return DynamoDbAsyncClient.builder()
                .region(Region.of(region))
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test", "test")))
                .httpClientBuilder(NettyNioAsyncHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5))
                        .readTimeout(Duration.ofSeconds(10)))
                .build();
    }

    @Bean
    @Profile({"local", "test"})
    public DynamoDbClient dynamoDbSyncClientLocal(
            @Value("${aws.dynamodb.endpoint:http://localhost:4566}") String endpoint) {
        return DynamoDbClient.builder()
                .region(Region.of(region))
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test", "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5)))
                .build();
    }

    // ── Production / Staging → ECS Task IAM Role (no hardcoded creds) ────────

    @Bean
    @Profile({"prod", "staging"})
    public DynamoDbAsyncClient dynamoDbAsyncClientProd() {
        return DynamoDbAsyncClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .httpClientBuilder(NettyNioAsyncHttpClient.builder()
                        .maxConcurrency(200)
                        .connectionTimeout(Duration.ofSeconds(5))
                        .readTimeout(Duration.ofSeconds(10)))
                .build();
    }

    @Bean
    @Profile({"prod", "staging"})
    public DynamoDbClient dynamoDbSyncClientProd() {
        return DynamoDbClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5)))
                .build();
    }

    // ── Enhanced client — same bean for all profiles ──────────────────────────

    @Bean
    public DynamoDbEnhancedAsyncClient dynamoDbEnhancedAsyncClient(
            DynamoDbAsyncClient dynamoDbAsyncClient) {
        return DynamoDbEnhancedAsyncClient.builder()
                .dynamoDbClient(dynamoDbAsyncClient)
                .build();
    }
}