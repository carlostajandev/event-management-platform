package com.nequi.ticketing.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClientBuilder;

import java.net.URI;

@Configuration(proxyBeanMethods = false)
class AwsClientsConfig {

    @Bean(destroyMethod = "close")
    DynamoDbAsyncClient dynamoDbAsyncClient(TicketingProperties properties) {
        TicketingProperties.Aws aws = properties.aws();
        DynamoDbAsyncClientBuilder builder = DynamoDbAsyncClient.builder()
                .region(Region.of(aws.region()))
                .httpClientBuilder(NettyNioAsyncHttpClient.builder())
                .overrideConfiguration(config -> config
                        .apiCallTimeout(aws.apiCallTimeout())
                        .apiCallAttemptTimeout(aws.apiCallAttemptTimeout()));
        if (StringUtils.hasText(aws.dynamodbEndpoint())) {
            builder.endpointOverride(URI.create(aws.dynamodbEndpoint()));
        }
        return builder.build();
    }
}
