package com.nequi.reservationservice.application.usecase;

import com.nequi.reservationservice.application.dto.ReservationResponse;
import com.nequi.reservationservice.application.mapper.ReservationMapper;
import com.nequi.reservationservice.application.port.in.GetReservationUseCase;
import com.nequi.shared.domain.exception.ReservationNotFoundException;
import com.nequi.shared.domain.port.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Application service implementing the get-reservation use case.
 *
 * <p>Simple read-through: delegates to the repository, maps the result via
 * {@link ReservationMapper}, and propagates {@link ReservationNotFoundException}
 * for missing records.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GetReservationService implements GetReservationUseCase {

    private final ReservationRepository reservationRepository;
    private final ReservationMapper     reservationMapper;

    @Override
    public Mono<ReservationResponse> execute(String reservationId) {
        return reservationRepository.findById(reservationId)
                .switchIfEmpty(Mono.error(new ReservationNotFoundException(reservationId)))
                .map(reservationMapper::toResponse)
                .doOnSuccess(response ->
                        log.debug("Reservation retrieved: reservationId={}", reservationId))
                .doOnError(ReservationNotFoundException.class, ex ->
                        log.warn("Reservation not found: reservationId={}", reservationId))
                .doOnError(ex -> {
                    if (!(ex instanceof ReservationNotFoundException)) {
                        log.error("Error retrieving reservation: reservationId={}, error={}",
                                reservationId, ex.getMessage(), ex);
                    }
                });
    }
}
