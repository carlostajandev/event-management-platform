package com.nequi.consumerservice.application.usecase;

import com.nequi.consumerservice.application.ConsumerConstants;
import com.nequi.shared.domain.exception.OrderNotFoundException;
import com.nequi.shared.domain.model.AuditEntry;
import com.nequi.shared.domain.model.OrderStatus;
import com.nequi.shared.domain.model.ReservationStatus;
import com.nequi.shared.domain.port.AuditRepository;
import com.nequi.shared.domain.port.OrderRepository;
import com.nequi.shared.domain.port.ReservationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;

/**
 * Application service that processes ORDER_PLACED events consumed from SQS.
 *
 * <p>This service is the core of the consumer-service. It transitions an order from
 * {@code PENDING_CONFIRMATION} to {@code CONFIRMED}, and simultaneously confirms
 * the linked reservation. Both state transitions are audited.
 *
 * <p>Idempotency guarantee: if the order is already {@code CONFIRMED} (possible on SQS retry),
 * the service returns {@link Mono#empty()} without re-processing. This prevents double-confirmation
 * even if the SQS message is delivered more than once.
 *
 * <p>Failure behavior: on any error, the exception is logged and re-thrown. SQS will
 * redeliver the message (visibility timeout must exceed max processing time). After 3
 * failed attempts, the message is moved to the Dead Letter Queue (emp-purchase-orders-dlq)
 * for manual investigation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessOrderService {

    private final OrderRepository       orderRepository;
    private final ReservationRepository reservationRepository;
    private final AuditRepository       auditRepository;
    private final MeterRegistry         meterRegistry;
    private final Clock                 clock;

    /**
     * Confirms an order and its linked reservation.
     *
     * @param orderId the order identifier from the SQS message payload
     * @return a {@link Mono} that completes when the order is fully confirmed and audited
     */
    public Mono<Void> process(String orderId) {
        Timer.Sample timerSample = Timer.start(meterRegistry);
        MDC.put("orderId", orderId);
        Instant now = Instant.now(clock);

        return orderRepository.findById(orderId)
                // Step 1: Order must exist
                .switchIfEmpty(Mono.error(new OrderNotFoundException(orderId)))
                .flatMap(order -> {
                    // Step 2: Idempotency guard — already confirmed, skip
                    if (order.status() == OrderStatus.CONFIRMED) {
                        log.info("Order already CONFIRMED, skipping idempotent redelivery: orderId={}", orderId);
                        return Mono.empty();
                    }

                    String reservationId = order.reservationId();
                    String userId = order.userId();

                    // Step 3: Confirm the order
                    return orderRepository.updateStatus(orderId, OrderStatus.CONFIRMED)
                            .flatMap(confirmedOrder ->
                                // Step 4: Confirm the linked reservation
                                reservationRepository.updateStatus(reservationId, ReservationStatus.CONFIRMED)
                                        .flatMap(confirmedReservation ->
                                            // Step 5: Audit order transition
                                            auditRepository.save(AuditEntry.create(
                                                    orderId,
                                                    ConsumerConstants.AUDIT_ENTITY_ORDER,
                                                    ConsumerConstants.AUDIT_STATUS_PENDING,
                                                    ConsumerConstants.AUDIT_STATUS_CONFIRMED,
                                                    userId, orderId, now
                                            )).flatMap(orderAudit ->
                                                // Step 6: Audit reservation transition
                                                auditRepository.save(AuditEntry.create(
                                                        reservationId,
                                                        ConsumerConstants.AUDIT_ENTITY_RESERVATION,
                                                        ConsumerConstants.AUDIT_STATUS_ACTIVE,
                                                        ConsumerConstants.AUDIT_STATUS_CONFIRMED,
                                                        userId, orderId, now
                                                ))
                                            )
                                        )
                            )
                            .doOnSuccess(v -> {
                                // Step 7: Record metrics
                                meterRegistry.counter(ConsumerConstants.METRIC_ORDERS_PROCESSED).increment();
                                timerSample.stop(meterRegistry.timer(ConsumerConstants.METRIC_ORDER_PROCESSING_TIMER));
                                log.info("Order confirmed: orderId={}, reservationId={}", orderId, reservationId);
                            });
                })
                .then()
                .doOnError(ex -> log.error("Failed to process order: orderId={}, error={}", orderId, ex.getMessage(), ex))
                .doFinally(signal -> MDC.remove("orderId"));
    }
}
