package com.nequi.ticketing.application.idempotency;

import com.nequi.ticketing.domain.exception.DuplicateRequestException;
import com.nequi.ticketing.domain.exception.IdempotencyKeyReusedException;
import com.nequi.ticketing.domain.model.IdempotencyKey;
import com.nequi.ticketing.domain.model.IdempotencyRecord;
import com.nequi.ticketing.domain.port.IdempotencyRepository;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IdempotentReplayTest {

    private static final IdempotencyKey KEY = new IdempotencyKey("user-1", "key-1");
    private static final IdempotencyRecord RECORD = new IdempotencyRecord(KEY, "hash", "order-1", Instant.EPOCH);

    private final IdempotencyRepository repository = mock(IdempotencyRepository.class);
    private final IdempotentReplay replay = new IdempotentReplay(repository);
    private final AtomicInteger writes = new AtomicInteger();

    @Test
    void writesWhenTheKeyIsNew() {
        when(repository.find(KEY)).thenReturn(Mono.empty());

        StepVerifier.create(replay.execute(KEY, "hash", this::write, id -> Mono.just("loaded " + id)))
                .expectNext("written")
                .verifyComplete();
        assertThat(writes).hasValue(1);
    }

    @Test
    void replaysAKnownKeyWithoutWriting() {
        when(repository.find(KEY)).thenReturn(Mono.just(RECORD));

        StepVerifier.create(replay.execute(KEY, "hash", this::write, id -> Mono.just("loaded " + id)))
                .expectNext("loaded order-1")
                .verifyComplete();
        assertThat(writes).hasValue(0);
    }

    @Test
    void rejectsAKnownKeyWithADifferentRequest() {
        when(repository.find(KEY)).thenReturn(Mono.just(RECORD));

        StepVerifier.create(replay.execute(KEY, "other-hash", this::write, id -> Mono.just("loaded " + id)))
                .expectError(IdempotencyKeyReusedException.class)
                .verify();
    }

    @Test
    void theLoserOfAConcurrentRaceIsAnsweredAsAReplay() {
        when(repository.find(KEY)).thenReturn(Mono.empty(), Mono.just(RECORD));

        StepVerifier.create(replay.execute(KEY, "hash", () -> Mono.error(new DuplicateRequestException(KEY)),
                        id -> Mono.just("loaded " + id)))
                .expectNext("loaded order-1")
                .verifyComplete();
    }

    @Test
    void propagatesTheDuplicateIfTheRecordVanished() {
        when(repository.find(KEY)).thenReturn(Mono.empty(), Mono.empty());

        StepVerifier.create(replay.execute(KEY, "hash", () -> Mono.<String>error(new DuplicateRequestException(KEY)),
                        id -> Mono.just("loaded " + id)))
                .expectError(DuplicateRequestException.class)
                .verify();
    }

    private Mono<String> write() {
        writes.incrementAndGet();
        return Mono.just("written");
    }
}
