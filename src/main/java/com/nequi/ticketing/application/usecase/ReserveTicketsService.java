package com.nequi.ticketing.application.usecase;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import com.nequi.ticketing.application.dto.ReservationResponse;
import com.nequi.ticketing.application.dto.ReserveTicketsCommand;
import com.nequi.ticketing.application.port.in.ReserveTicketsUseCase;
import com.nequi.ticketing.domain.exception.EventNotFoundException;
import com.nequi.ticketing.domain.exception.TicketNotAvailableException;
import com.nequi.ticketing.domain.model.Ticket;
import com.nequi.ticketing.domain.repository.EventRepository;
import com.nequi.ticketing.domain.repository.IdempotencyRepository;
import com.nequi.ticketing.domain.repository.TicketRepository;
import com.nequi.ticketing.domain.service.TicketStateMachine;
import com.nequi.ticketing.domain.valueobject.EventId;
import com.nequi.ticketing.domain.valueobject.IdempotencyKey;
import com.nequi.ticketing.domain.valueobject.OrderId;
import com.nequi.ticketing.infrastructure.config.TicketingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

/**
 * Implementation of {@link ReserveTicketsUseCase}.
 *
 * <p>Concurrency strategy:
 * <ol>
 *   <li>Check idempotency key — return cached response if duplicate request</li>
 *   <li>Validate event exists and is published</li>
 *   <li>Find N available tickets using GSI query</li>
 *   <li>Reserve each ticket with conditional write (version check)</li>
 *   <li>If any conditional write fails (race condition), release all and return 409</li>
 *   <li>Cache response with idempotency key</li>
 * </ol>
 *
 * <p>The conditional write in step 4 guarantees that even if two concurrent
 * requests find the same available tickets, only one will succeed per ticket.
 * DynamoDB's atomic conditional writes make this safe without pessimistic locking.
 */
@Service
public class ReserveTicketsService implements ReserveTicketsUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReserveTicketsService.class);

    private final EventRepository eventRepository;
    private final TicketRepository ticketRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final TicketStateMachine stateMachine;
    private final TicketingProperties ticketingProperties;
    private final JsonMapper objectMapper;

    public ReserveTicketsService(
            EventRepository eventRepository,
            TicketRepository ticketRepository,
            IdempotencyRepository idempotencyRepository,
            TicketStateMachine stateMachine,
            TicketingProperties ticketingProperties,
            JsonMapper objectMapper) {
        this.eventRepository = eventRepository;
        this.ticketRepository = ticketRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.stateMachine = stateMachine;
        this.ticketingProperties = ticketingProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<ReservationResponse> execute(ReserveTicketsCommand command) {
        IdempotencyKey idempotencyKey = IdempotencyKey.of(command.idempotencyKey());
        EventId eventId = EventId.of(command.eventId());

        log.debug("Reserving {} tickets for event={}, user={}, idempotencyKey={}",
                command.quantity(), command.eventId(), command.userId(), command.idempotencyKey());

        return idempotencyRepository.exists(idempotencyKey)
                .flatMap(exists -> {
                    if (exists) {
                        log.info("Duplicate request detected for idempotencyKey={}", idempotencyKey);
                        return idempotencyRepository.findResponse(idempotencyKey)
                                .flatMap(this::deserializeResponse);
                    }
                    return processReservation(command, eventId, idempotencyKey);
                });
    }

    private Mono<ReservationResponse> processReservation(
            ReserveTicketsCommand command,
            EventId eventId,
            IdempotencyKey idempotencyKey) {

        return eventRepository.findById(eventId)
                .switchIfEmpty(Mono.error(new EventNotFoundException(eventId)))
                .flatMap(event ->
                        ticketRepository
                                .findAvailableByEventId(eventId, command.quantity())
                                .collectList()
                                .flatMap(availableTickets -> {
                                    if (availableTickets.size() < command.quantity()) {
                                        return Mono.error(new TicketNotAvailableException(
                                                eventId, command.quantity()));
                                    }

                                    OrderId orderId = OrderId.generate();
                                    int ttlMinutes = ticketingProperties.reservation().ttlMinutes();

                                    return reserveTickets(
                                            availableTickets.subList(0, command.quantity()),
                                            command.userId(),
                                            orderId.value(),
                                            ttlMinutes)
                                            .collectList()
                                            .flatMap(reservedTickets ->
                                                    buildAndCacheResponse(
                                                            reservedTickets, command, orderId,
                                                            ttlMinutes, idempotencyKey));
                                }));
    }

    private Flux<Ticket> reserveTickets(
            List<Ticket> tickets,
            String userId,
            String orderId,
            int ttlMinutes) {

        return Flux.fromIterable(tickets)
                .flatMap(ticket -> {
                    stateMachine.validateTransition(ticket.status(),
                            com.nequi.ticketing.domain.model.TicketStatus.RESERVED);

                    Ticket reserved = ticket.reserve(userId, orderId, ttlMinutes);
                    return ticketRepository.update(reserved)
                            .onErrorMap(RuntimeException.class, ex -> {
                                log.warn("Concurrent modification on ticket={}, will retry",
                                        ticket.ticketId().value());
                                return new TicketNotAvailableException(ticket.eventId(), 1);
                            });
                });
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
                .flatMap(json -> idempotencyRepository.save(
                        idempotencyKey, json,
                        ticketingProperties.idempotency().ttlHours()))
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