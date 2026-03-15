package com.nequi.ticketing.application.usecase;

import com.nequi.ticketing.application.port.in.ReleaseExpiredReservationsUseCase;
import com.nequi.ticketing.domain.repository.TicketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Finds expired RESERVED tickets and releases them back to AVAILABLE.
 *
 * <p>A reservation is expired when:
 * status == RESERVED AND expiresAt < now
 *
 * <p>This service is called by {@link com.nequi.ticketing.infrastructure.scheduler.ExpiredReservationScheduler}
 * every 60 seconds (configurable via ticketing.reservation.expiry-job-interval).
 */
@Service
public class ReleaseExpiredReservationsService implements ReleaseExpiredReservationsUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReleaseExpiredReservationsService.class);

    private final TicketRepository ticketRepository;

    public ReleaseExpiredReservationsService(TicketRepository ticketRepository) {
        this.ticketRepository = ticketRepository;
    }

    @Override
    public Mono<Long> execute() {
        Instant now = Instant.now();
        log.debug("Scanning for expired reservations before {}", now);

        return ticketRepository.findExpiredReservations(now)
                .flatMap(ticket -> {
                    log.debug("Releasing expired reservation: ticketId={}, expiresAt={}",
                            ticket.ticketId().value(), ticket.expiresAt());
                    return ticketRepository.update(ticket.release());
                })
                .count()
                .doOnSuccess(count -> {
                    if (count > 0) {
                        log.info("Released {} expired reservations", count);
                    }
                });
    }
}
