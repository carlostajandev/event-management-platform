package com.nequi.ticketing.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.net.URI;
import java.time.Duration;

/**
 * DynamoDB client configuration with profile-based credential separation.
 *
 * <p>Security pattern — two credential strategies:
 * <ul>
 *   <li><b>local/test</b>: StaticCredentialsProvider with fake keys for
 *       DynamoDB Local and LocalStack. Never used in production.</li>
 *   <li><b>prod/staging</b>: DefaultCredentialsProvider resolves automatically:
 *       1. ECS Task IAM Role (container metadata endpoint 169.254.170.2)
 *       2. EC2 Instance Profile
 *       3. ~/.aws/credentials (developer machine)
 *       No credentials are hardcoded. No access keys in environment variables.</li>
 * </ul>
 *
 * <p>Virtual Threads (Java 25): The async SDK uses Virtual Threads for
 * future completion — eliminates blocking platform threads in the completion
 * stage, freeing Netty event loop threads faster.
 */
@Configuration
public class DynamoDbConfig {

    // ── LOCAL / TEST ──────────────────────────────────────────────────────────

    @Bean
    @Profile({"local", "test"})
    public DynamoDbAsyncClient dynamoDbAsyncClientLocal(AwsProperties props) {
        return DynamoDbAsyncClient.builder()
                .region(Region.of(props.region()))
                .endpointOverride(URI.create(props.dynamodb().endpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("fakeMyKeyId", "fakeSecretAccessKey")))
                .httpClientBuilder(NettyNioAsyncHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5))
                        .readTimeout(Duration.ofSeconds(30)))
                .build();
    }

    @Bean
    @Profile({"local", "test"})
    public DynamoDbClient dynamoDbSyncClientLocal(AwsProperties props) {
        return DynamoDbClient.builder()
                .region(Region.of(props.region()))
                .endpointOverride(URI.create(props.dynamodb().endpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("fakeMyKeyId", "fakeSecretAccessKey")))
                .httpClient(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5))
                        .socketTimeout(Duration.ofSeconds(30))
                        .build())
                .build();
    }

    // ── PROD / STAGING ────────────────────────────────────────────────────────

    @Bean
    @Profile({"prod", "staging"})
    public DynamoDbAsyncClient dynamoDbAsyncClientProd(AwsProperties props) {
        return DynamoDbAsyncClient.builder()
                .region(Region.of(props.region()))
                // DefaultCredentialsProvider resolves automatically:
                // ECS Task IAM Role → EC2 Instance Profile → ~/.aws/credentials
                // No static credentials — compliant with security policy
                .credentialsProvider(DefaultCredentialsProvider.create())
                .httpClientBuilder(NettyNioAsyncHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5))
                        .readTimeout(Duration.ofSeconds(30)))
                .build();
    }

    @Bean
    @Profile({"prod", "staging"})
    public DynamoDbClient dynamoDbSyncClientProd(AwsProperties props) {
        return DynamoDbClient.builder()
                .region(Region.of(props.region()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .httpClient(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5))
                        .socketTimeout(Duration.ofSeconds(30))
                        .build())
                .build();
    }

    // ── SHARED ────────────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean
    public DynamoDbEnhancedAsyncClient dynamoDbEnhancedAsyncClient(
            DynamoDbAsyncClient dynamoDbAsyncClient) {
        return DynamoDbEnhancedAsyncClient.builder()
                .dynamoDbClient(dynamoDbAsyncClient)
                .build();
    }
}