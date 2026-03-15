package com.nequi.ticketing.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.net.URI;
import java.time.Duration;

@Configuration
public class DynamoDbConfig {

    private static final StaticCredentialsProvider CREDENTIALS =
            StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("fakeMyKeyId", "fakeSecretAccessKey"));

    @Bean
    public DynamoDbAsyncClient dynamoDbAsyncClient(AwsProperties awsProperties) {
        return DynamoDbAsyncClient.builder()
                .region(Region.of(awsProperties.region()))
                .endpointOverride(URI.create(awsProperties.dynamodb().endpoint()))
                .credentialsProvider(CREDENTIALS)
                .httpClientBuilder(NettyNioAsyncHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5))
                        .readTimeout(Duration.ofSeconds(30)))
                .build();
    }

    @Bean
    public DynamoDbEnhancedAsyncClient dynamoDbEnhancedAsyncClient(
            DynamoDbAsyncClient dynamoDbAsyncClient) {
        return DynamoDbEnhancedAsyncClient.builder()
                .dynamoDbClient(dynamoDbAsyncClient)
                .build();
    }

    @Bean
    public DynamoDbClient dynamoDbSyncClient(AwsProperties awsProperties) {
        return DynamoDbClient.builder()
                .region(Region.of(awsProperties.region()))
                .endpointOverride(URI.create(awsProperties.dynamodb().endpoint()))
                .credentialsProvider(CREDENTIALS)
                .httpClient(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5))
                        .socketTimeout(Duration.ofSeconds(30))
                        .build())
                .build();
    }
}