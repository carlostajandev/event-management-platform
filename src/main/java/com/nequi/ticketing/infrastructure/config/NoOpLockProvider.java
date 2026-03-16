package com.nequi.ticketing.infrastructure.config;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;

import java.util.Optional;

/**
 * No-operation LockProvider for test environments.
 *
 * In integration tests, ShedLock adds zero value — only one JVM instance runs,
 * so distributed locking is unnecessary. More importantly, using the real
 * DynamoDB provider in tests creates a race condition: the scheduler fires
 * before @PostConstruct finishes creating the shedlock table.
 *
 * This implementation always grants the lock immediately, making @SchedulerLock
 * transparent in tests while keeping the scheduler logic fully exercised.
 */
public class NoOpLockProvider implements LockProvider {

    @Override
    public Optional<SimpleLock> lock(LockConfiguration lockConfiguration) {
        return Optional.of(() -> { /* no-op unlock */ });
    }
}