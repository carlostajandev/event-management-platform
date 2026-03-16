package com.nequi.ticketing.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

import java.net.URI;

/**
 * SQS client configuration with profile-based credential separation.
 *
 * <p>Same security pattern as {@link DynamoDbConfig}:
 * <ul>
 *   <li><b>local/test</b>: Fake credentials pointing to LocalStack (:4566)</li>
 *   <li><b>prod/staging</b>: DefaultCredentialsProvider — ECS Task IAM Role,
 *       no hardcoded credentials anywhere.</li>
 * </ul>
 */
@Configuration
public class SqsConfig {

    // ── LOCAL / TEST ──────────────────────────────────────────────────────────

    @Bean
    @Profile({"local", "test"})
    public SqsAsyncClient sqsAsyncClientLocal(AwsProperties props) {
        return SqsAsyncClient.builder()
                .region(Region.of(props.region()))
                .endpointOverride(URI.create(props.sqs().endpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("local", "local")))
                .build();
    }

    // ── PROD / STAGING ────────────────────────────────────────────────────────

    @Bean
    @Profile({"prod", "staging"})
    public SqsAsyncClient sqsAsyncClientProd(AwsProperties props) {
        return SqsAsyncClient.builder()
                .region(Region.of(props.region()))
                // DefaultCredentialsProvider: ECS Task IAM Role in production
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}