package com.nequi.ticketing.infrastructure.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.dynamodb2.DynamoDBLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * ShedLock configuration for distributed scheduler coordination.
 *
 * <p>Problem: with multiple ECS instances running, the {@code @Scheduled}
 * expiry job would run on EVERY instance simultaneously — releasing the
 * same expired tickets N times and causing race conditions.
 *
 * <p>Solution: ShedLock uses a DynamoDB table ({@code emp-shedlock}) as
 * a distributed lock. Only ONE instance runs the job at a time.
 * Other instances skip that execution cycle.
 *
 * <p>Table: {@code emp-shedlock}
 * Schema: PK={@code _id} (String) — created manually or via DynamoDB init.
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT55S")
public class ShedLockConfig {

    /**
     * Uses the synchronous DynamoDB client (not async) because ShedLock
     * is inherently synchronous — lock acquisition must complete before
     * the scheduler runs.
     */
    @Bean
    public LockProvider lockProvider(DynamoDbClient dynamoDbSyncClient) {
        return new DynamoDBLockProvider(dynamoDbSyncClient, "emp-shedlock");
    }
}
