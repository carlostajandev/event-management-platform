package com.nequi.ticketing.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.MountableFile;

/** One ElasticMQ container per test JVM, configured with the same file docker compose uses. */
public final class LocalSqs {

    @SuppressWarnings("resource")
    private static final GenericContainer<?> ELASTICMQ = new GenericContainer<>("softwaremill/elasticmq-native:1.7.1")
            .withCopyFileToContainer(MountableFile.forHostPath("infrastructure/local/elasticmq.conf"), "/opt/elasticmq.conf")
            .withExposedPorts(9324);

    private LocalSqs() {
    }

    public static synchronized String endpoint() {
        if (!ELASTICMQ.isRunning()) {
            ELASTICMQ.start();
        }
        return "http://%s:%d".formatted(ELASTICMQ.getHost(), ELASTICMQ.getMappedPort(9324));
    }
}
