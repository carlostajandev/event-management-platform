package com.nequi.ticketing.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

import java.net.URI;

/**
 * AWS SQS client configuration.
 *
 * <p>Uses {@link SqsAsyncClient} for non-blocking publish/receive operations.
 * In local profile, endpoint override points to LocalStack (:4566).
 */
@Configuration
public class SqsConfig {

    @Bean
    public SqsAsyncClient sqsAsyncClient(AwsProperties awsProperties) {
        return SqsAsyncClient.builder()
                .region(Region.of(awsProperties.region()))
                .endpointOverride(URI.create(awsProperties.sqs().endpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("local", "local")
                ))
                .build();
    }
}
