package com.nequi.ticketing.application.usecase;

import com.nequi.ticketing.application.dto.ReservationResponse;
import com.nequi.ticketing.application.dto.ReserveTicketsCommand;
import com.nequi.ticketing.application.port.in.ReserveTicketsUseCase;
import com.nequi.ticketing.domain.exception.EventNotFoundException;
import com.nequi.ticketing.domain.exception.TicketNotAvailableException;
import com.nequi.ticketing.domain.model.Ticket;
import com.nequi.ticketing.domain.model.TicketStatus;
import com.nequi.ticketing.domain.repository.EventRepository;
import com.nequi.ticketing.domain.repository.IdempotencyRepository;
import com.nequi.ticketing.domain.repository.TicketRepository;
import com.nequi.ticketing.domain.service.TicketStateMachine;
import com.nequi.ticketing.domain.valueobject.EventId;
import com.nequi.ticketing.domain.valueobject.IdempotencyKey;
import com.nequi.ticketing.domain.valueobject.OrderId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Implements {@link ReserveTicketsUseCase} with optimistic concurrency retry.
 *
 * <p><b>Concurrency strategy:</b> instead of reading a snapshot of N tickets and
 * trying to reserve all of them at once (which fails under high concurrency because
 * multiple threads read the same available tickets before any write completes),
 * this service reserves one ticket at a time with per-ticket retry on
 * ConditionalCheckFailedException.
 *
 * <p><b>Why the original snapshot approach fails:</b>
 * With 100 concurrent requests for 50 tickets, all 100 threads read the same
 * 50 available tickets in their snapshot. Thread A and Thread B both pick ticket-1.
 * Thread A wins the conditional write; Thread B gets ConditionalCheckFailed, which
 * triggers full compensation and returns 409 — even though 49 other tickets are
 * still available. Result: only 1 thread succeeds instead of 50.
 *
 * <p><b>Fix — per-ticket retry:</b> each ticket slot is reserved independently.
 * On ConditionalCheckFailed, the thread fetches the next available ticket and
 * tries again (up to MAX_RETRIES times). This way 50 threads each win their own
 * ticket, and only the remaining 50 threads get a genuine 409 when the pool
 * is exhausted.
 *
 * <p><b>concatMap for multi-ticket orders:</b> tickets within a single order are
 * still reserved sequentially to maintain accurate compensation tracking.
 */
@Service
public class ReserveTicketsService implements ReserveTicketsUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReserveTicketsService.class);

    /**
     * Maximum retry attempts per ticket slot on concurrent modification.
     * Under normal load (< 200 concurrent requests), 10 retries is more than enough.
     * Each retry is a cheap GSI query + conditional put — no sleep/backoff needed
     * because DynamoDB latency itself provides natural spacing.
     */
    private static final int MAX_RETRIES = 10;

    private final EventRepository eventRepository;
    private final TicketRepository ticketRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final TicketStateMachine stateMachine;
    private final int reservationTtlMinutes;
    private final int idempotencyTtlHours;
    private final ObjectMapper objectMapper;

    public ReserveTicketsService(
            EventRepository eventRepository,
            TicketRepository ticketRepository,
            IdempotencyRepository idempotencyRepository,
            TicketStateMachine stateMachine,
            @Value("${ticketing.reservation.ttl-minutes:10}") int reservationTtlMinutes,
            @Value("${ticketing.idempotency.ttl-hours:24}") int idempotencyTtlHours,
            ObjectMapper objectMapper) {
        this.eventRepository = eventRepository;
        this.ticketRepository = ticketRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.stateMachine = stateMachine;
        this.reservationTtlMinutes = reservationTtlMinutes;
        this.idempotencyTtlHours = idempotencyTtlHours;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<ReservationResponse> execute(ReserveTicketsCommand command) {
        IdempotencyKey idempotencyKey = IdempotencyKey.of(command.idempotencyKey());
        EventId eventId = EventId.of(command.eventId());

        log.debug("Reserving {} tickets for event={}, user={}, idempotencyKey={}",
                command.quantity(), command.eventId(), command.userId(), command.idempotencyKey());

        // No more exists() check — claimIdempotencyKey handles the race atomically
        return processReservation(command, eventId, idempotencyKey);
    }

    private Mono<ReservationResponse> processReservation(
            ReserveTicketsCommand command,
            EventId eventId,
            IdempotencyKey idempotencyKey) {

        OrderId orderId = OrderId.generate();

        // 1. Claim the idempotency key FIRST — only one concurrent request wins this race.
        //    If we lost the race, return cached response without touching eventRepository.
        return claimIdempotencyKey(idempotencyKey, orderId)
                .flatMap(claimed -> {
                    if (!claimed) {
                        // Lost the race — fetch what the winner cached
                        log.info("Duplicate request detected for idempotencyKey={}",
                                idempotencyKey);
                        return idempotencyRepository.findResponse(idempotencyKey)
                                .flatMap(json -> {
                                    if (json.startsWith("__pending__:")) {
                                        // Winner is still processing — wait and retry
                                        return Mono.delay(java.time.Duration.ofMillis(200))
                                                .flatMap(__ -> idempotencyRepository.findResponse(idempotencyKey))
                                                .repeat(10)
                                                .filter(j -> !j.startsWith("__pending__:"))
                                                .next()
                                                .flatMap(this::deserializeResponse);
                                    }
                                    return deserializeResponse(json);
                                });
                    }
                    // 2. Won the race — now look up the event and reserve tickets
                    return eventRepository.findById(eventId)
                            .switchIfEmpty(Mono.error(new EventNotFoundException(eventId)))
                            .flatMap(event ->
                                    reserveWithRetry(command, eventId, orderId)
                                            .flatMap(reservedTickets ->
                                                    buildAndCacheResponse(
                                                            reservedTickets, command,
                                                            orderId, reservationTtlMinutes,
                                                            idempotencyKey)));
                });
    }
    private Mono<Boolean> claimIdempotencyKey(IdempotencyKey key, OrderId orderId) {
        // Save a placeholder — the real response is updated after reservation succeeds
        return idempotencyRepository.save(key, "__pending__:" + orderId.value(),
                        idempotencyTtlHours)
                .thenReturn(true)
                .onErrorResume(ex ->
                        // ConditionalCheckFailed = another request already claimed it
                        Mono.just(false));
    }

    /**
     * Reserves N tickets sequentially, each with independent retry on conflict.
     *
     * <p>Uses concatMap so that if ticket slot K fails after slots 1..K-1 succeed,
     * we have an accurate {@code reservedSoFar} list for compensation.
     */
    private Mono<List<Ticket>> reserveWithRetry(
            ReserveTicketsCommand command,
            EventId eventId,
            OrderId orderId) {

        List<Ticket> reservedSoFar = new ArrayList<>();

        return Flux.range(0, command.quantity())
                .concatMap(i -> reserveOneTicketWithRetry(
                        eventId, command.userId(), orderId.value(), reservationTtlMinutes))
                .doOnNext(reservedSoFar::add)
                .collectList()
                .onErrorResume(ex -> {
                    log.warn("Reservation failed after {} tickets reserved — compensating",
                            reservedSoFar.size());

                    return Flux.fromIterable(reservedSoFar)
                            .concatMap(t -> ticketRepository.update(t.release())
                                    .onErrorResume(releaseEx -> {
                                        log.error("Failed to release ticket={} during compensation: {}",
                                                t.ticketId().value(), releaseEx.getMessage());
                                        return Mono.empty();
                                    }))
                            .then(Mono.error(new TicketNotAvailableException(
                                    eventId, command.quantity())));
                });
    }

    /**
     * Attempts to reserve one available ticket for the given event.
     *
     * <p>On ConditionalCheckFailed (another thread won the race for this specific
     * ticket), fetches the next available ticket and retries. If the pool is
     * exhausted before MAX_RETRIES, throws {@link TicketNotAvailableException}.
     *
     * <p>No sleep/backoff between retries — DynamoDB round-trip latency (~5ms)
     * provides natural spacing under concurrent load.
     */
    private Mono<Ticket> reserveOneTicketWithRetry(
            EventId eventId,
            String userId,
            String orderId,
            int ttlMinutes) {

        return Flux.range(0, MAX_RETRIES)
                .concatMap(attempt ->
                        ticketRepository.findAvailableByEventId(eventId, 1)
                                .next()
                                .switchIfEmpty(Mono.error(new TicketNotAvailableException(eventId, 1)))
                                .flatMap(ticket -> {
                                    stateMachine.validateTransition(ticket.status(), TicketStatus.RESERVED);
                                    Ticket reserved = ticket.reserve(userId, orderId, ttlMinutes);
                                    return ticketRepository.update(reserved);
                                })
                                .onErrorResume(ex -> {
                                    if (isConcurrentModification(ex)) {
                                        // Backoff exponencial
                                        long delay = calculateBackoff(attempt);
                                        log.debug("Concurrent modification on attempt {}, retrying in {}ms",
                                                attempt, delay);
                                        return Mono.delay(Duration.ofMillis(delay))
                                                .then(Mono.error(new RetryableException()));
                                    }
                                    return Mono.error(ex);
                                })
                )
                .retryWhen(Retry.backoff(MAX_RETRIES, Duration.ofMillis(50))
                        .maxBackoff(Duration.ofMillis(500))
                        .filter(RetryableException.class::isInstance))
                .next()
                .switchIfEmpty(Mono.error(new TicketNotAvailableException(eventId, 1)));
    }

    private long calculateBackoff(int attempt) {
        return Math.min(100 * (long) Math.pow(2, attempt), 1000);
    }

    // Excepción interna para reintentos
    private static class RetryableException extends RuntimeException {}

    /**
     * Detects a lost optimistic lock race from the error message set in
     * {@code TicketDynamoDbRepository.update()}.
     */
    private boolean isConcurrentModification(Throwable ex) {
        return ex.getMessage() != null
                && ex.getMessage().startsWith("Concurrent modification on ticket:");
    }


    private Mono<ReservationResponse> buildAndCacheResponse(
            List<Ticket> reservedTickets,
            ReserveTicketsCommand command,
            OrderId orderId,
            int ttlMinutes,
            IdempotencyKey idempotencyKey) {

        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds((long) ttlMinutes * 60);

        List<String> ticketIds = reservedTickets.stream()
                .map(t -> t.ticketId().value())
                .toList();

        ReservationResponse response = new ReservationResponse(
                orderId.value(),
                command.eventId(),
                command.userId(),
                command.quantity(),
                ticketIds,
                now,
                expiresAt,
                "RESERVED"
        );

        return serializeResponse(response)
                // Update replaces the placeholder — no condition needed here
                // since we own this key (we won the claim race above)
                .flatMap(json -> idempotencyRepository.updateResponse(idempotencyKey, json))
                .thenReturn(response)
                .doOnSuccess(r -> log.info(
                        "Tickets reserved: orderId={}, eventId={}, quantity={}, expiresAt={}",
                        orderId.value(), command.eventId(), command.quantity(), expiresAt));
    }

    private Mono<String> serializeResponse(ReservationResponse response) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(response))
                .onErrorMap(JacksonException.class,
                        ex -> new RuntimeException("Failed to serialize response", ex));
    }

    private Mono<ReservationResponse> deserializeResponse(String json) {
        return Mono.fromCallable(() -> objectMapper.readValue(json, ReservationResponse.class))
                .onErrorMap(Exception.class,
                        ex -> new RuntimeException("Failed to deserialize cached response", ex));
    }
}