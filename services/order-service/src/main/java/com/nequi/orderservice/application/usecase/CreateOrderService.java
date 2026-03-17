package com.nequi.orderservice.application.usecase;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nequi.orderservice.application.OrderConstants;
import com.nequi.orderservice.application.command.CreateOrderCommand;
import com.nequi.orderservice.application.dto.OrderResponse;
import com.nequi.orderservice.application.mapper.OrderMapper;
import com.nequi.orderservice.application.port.in.CreateOrderUseCase;
import com.nequi.shared.domain.exception.ReservationNotFoundException;
import com.nequi.shared.domain.model.AuditEntry;
import com.nequi.shared.domain.model.IdempotencyRecord;
import com.nequi.shared.domain.model.Order;
import com.nequi.shared.domain.model.OrderStatus;
import com.nequi.shared.domain.model.ReservationStatus;
import com.nequi.shared.domain.port.AuditRepository;
import com.nequi.shared.domain.port.IdempotencyRepository;
import com.nequi.shared.domain.port.OrderRepository;
import com.nequi.shared.domain.port.ReservationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Application service implementing the create-order use case.
 *
 * <p>Critical invariants enforced:
 * <ol>
 *   <li><strong>Idempotency first</strong>: checks the idempotency cache before any business logic.
 *       Duplicate requests with the same key return the cached response without side effects.</li>
 *   <li><strong>Reservation ownership</strong>: validates that the requesting userId matches the
 *       reservation owner — prevents order theft between users.</li>
 *   <li><strong>Status guard</strong>: only ACTIVE reservations can generate orders. EXPIRED,
 *       CONFIRMED, or CANCELLED reservations result in {@link IllegalStateException}.</li>
 *   <li><strong>Atomic write</strong>: order + outbox message written in a single DynamoDB
 *       TransactWriteItems call. If SQS is down, the OutboxPoller (consumer-service) will
 *       retry delivery with at-least-once semantics.</li>
 * </ol>
 *
 * <p>All monetary values are handled via {@code BigDecimal} — never floating-point arithmetic.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreateOrderService implements CreateOrderUseCase {

    private final ReservationRepository reservationRepository;
    private final OrderRepository       orderRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final AuditRepository       auditRepository;
    private final ObjectMapper          objectMapper;
    private final OrderMapper           orderMapper;
    private final MeterRegistry         meterRegistry;
    private final Clock                 clock;

    @Override
    public Mono<OrderResponse> execute(CreateOrderCommand command) {
        MDC.put("idempotencyKey", command.idempotencyKey());
        MDC.put("reservationId", command.reservationId());

        // Step 1: Check idempotency cache — short-circuit on duplicate requests
        return idempotencyRepository.findByKey(command.idempotencyKey())
                .flatMap(existing -> deserializeCachedResponse(existing.cachedResponseJson()))
                .switchIfEmpty(Mono.defer(() -> processNewOrder(command)))
                .doFinally(signal -> {
                    MDC.remove("idempotencyKey");
                    MDC.remove("reservationId");
                });
    }

    // ── New order processing pipeline ─────────────────────────────────────────

    private Mono<OrderResponse> processNewOrder(CreateOrderCommand command) {
        String idempotencyKey = command.idempotencyKey();
        Instant now = Instant.now(clock);

        return reservationRepository.findById(command.reservationId())
                // Step 2: Reservation must exist
                .switchIfEmpty(Mono.error(new ReservationNotFoundException(command.reservationId())))
                .flatMap(reservation -> {
                    // Step 3: Reservation must be ACTIVE
                    if (reservation.status() != ReservationStatus.ACTIVE) {
                        return Mono.error(new IllegalStateException(
                                "Reservation is not ACTIVE. Current status: " + reservation.status()
                                + ", reservationId=" + command.reservationId()));
                    }
                    // Step 4: Reservation must belong to the requesting user
                    if (!reservation.userId().equals(command.userId())) {
                        return Mono.error(new IllegalArgumentException(
                                "UserId mismatch: reservation belongs to a different user. "
                                + "reservationId=" + command.reservationId()));
                    }

                    // Step 5: Build the Order aggregate
                    String orderId = UUID.randomUUID().toString();
                    Order order = new Order(
                            orderId,
                            reservation.id(),
                            reservation.eventId(),
                            reservation.userId(),
                            reservation.seatsCount(),
                            reservation.totalAmount(),
                            reservation.currency(),
                            OrderStatus.PENDING_CONFIRMATION,
                            idempotencyKey,
                            now,
                            now
                    );

                    OrderResponse response = orderMapper.toResponse(order);
                    String responseJson = serializeResponse(response);
                    String outboxPayload = buildOutboxPayload(order);

                    // Step 6: Atomic TransactWriteItems — order + outbox
                    return orderRepository.saveWithOutbox(order, outboxPayload)
                            .flatMap(savedOrder ->
                                // Step 7: Write idempotency record (24h TTL)
                                idempotencyRepository.save(
                                        IdempotencyRecord.create(idempotencyKey, savedOrder.id(), responseJson)
                                ).flatMap(idempotencyRecord ->
                                    // Step 8: Write audit trail
                                    auditRepository.save(AuditEntry.create(
                                            savedOrder.id(),
                                            OrderConstants.AUDIT_ENTITY_TYPE,
                                            OrderConstants.AUDIT_STATUS_NONE,
                                            OrderConstants.AUDIT_STATUS_PENDING,
                                            savedOrder.userId(),
                                            idempotencyKey,
                                            now
                                    )).thenReturn(savedOrder)
                                )
                            )
                            .map(savedOrder -> {
                                // Step 9: Increment Micrometer counter
                                meterRegistry.counter(OrderConstants.METRIC_ORDERS_CREATED).increment();
                                MDC.put("orderId", savedOrder.id());
                                log.info("Order created: orderId={}, reservationId={}, userId={}, status={}",
                                        savedOrder.id(), savedOrder.reservationId(),
                                        savedOrder.userId(), savedOrder.status());
                                MDC.remove("orderId");
                                return response;
                            });
                })
                .doOnError(ReservationNotFoundException.class, ex ->
                        log.warn("Reservation not found: reservationId={}", command.reservationId()))
                .doOnError(IllegalStateException.class, ex ->
                        log.warn("Reservation not ACTIVE: {}", ex.getMessage()))
                .doOnError(IllegalArgumentException.class, ex ->
                        log.warn("UserId mismatch: {}", ex.getMessage()))
                .doOnError(ex -> {
                    if (!(ex instanceof ReservationNotFoundException)
                            && !(ex instanceof IllegalStateException)
                            && !(ex instanceof IllegalArgumentException)) {
                        log.error("Unexpected error creating order: reservationId={}, error={}",
                                command.reservationId(), ex.getMessage(), ex);
                    }
                });
    }

    // ── Serialization helpers ─────────────────────────────────────────────────

    private Mono<OrderResponse> deserializeCachedResponse(String json) {
        try {
            OrderResponse cached = objectMapper.readValue(json, OrderResponse.class);
            log.info("Returning cached idempotency response for orderId={}", cached.id());
            return Mono.just(cached);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize cached idempotency response: {}", e.getMessage(), e);
            return Mono.empty(); // fall through to re-process
        }
    }

    private String serializeResponse(OrderResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize OrderResponse for idempotency cache: {}", e.getMessage(), e);
            return "{}";
        }
    }

    private String buildOutboxPayload(Order order) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "orderId", order.id(),
                    "reservationId", order.reservationId(),
                    "eventId", order.eventId(),
                    "userId", order.userId(),
                    "seatsCount", order.seatsCount(),
                    "eventType", OrderConstants.OUTBOX_EVENT_TYPE
            ));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize outbox payload for orderId={}: {}", order.id(), e.getMessage(), e);
            return "{}";
        }
    }
}
