package com.nequi.shared.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;

import java.net.URI;
import java.time.Duration;

@Configuration
public class SqsConfig {

    @Value("${aws.region:us-east-1}")
    private String region;

    @Bean
    @Profile({"local", "test"})
    public SqsAsyncClient sqsAsyncClientLocal(
            @Value("${aws.sqs.endpoint:http://localhost:4566}") String endpoint) {
        return SqsAsyncClient.builder()
                .region(Region.of(region))
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test", "test")))
                .httpClientBuilder(NettyNioAsyncHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5)))
                .build();
    }

    @Bean
    @Profile({"prod", "staging"})
    public SqsAsyncClient sqsAsyncClientProd() {
        return SqsAsyncClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .httpClientBuilder(NettyNioAsyncHttpClient.builder()
                        .maxConcurrency(100)
                        .connectionTimeout(Duration.ofSeconds(5)))
                .build();
    }
}