package com.nequi.ticketing.infrastructure.config;

import com.nequi.ticketing.application.event.CreateEventUseCase;
import com.nequi.ticketing.application.event.GetAvailabilityUseCase;
import com.nequi.ticketing.application.event.GetEventUseCase;
import com.nequi.ticketing.application.event.ListEventsUseCase;
import com.nequi.ticketing.application.idempotency.IdempotentReplay;
import com.nequi.ticketing.application.order.ConfirmOrderUseCase;
import com.nequi.ticketing.application.order.GetOrderUseCase;
import com.nequi.ticketing.application.order.IssueComplimentaryUseCase;
import com.nequi.ticketing.application.order.PlaceOrderUseCase;
import com.nequi.ticketing.application.order.ProcessPlacedOrderUseCase;
import com.nequi.ticketing.application.order.ReleaseExpiredHoldsUseCase;
import com.nequi.ticketing.domain.port.EventRepository;
import com.nequi.ticketing.domain.port.IdempotencyRepository;
import com.nequi.ticketing.domain.port.OrderRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/** Wires the framework-free use cases. */
@Configuration(proxyBeanMethods = false)
class UseCaseConfig {

    /** Millisecond ticks: the precision timestamps are stored with, so a replayed response equals the original. */
    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofMillis(1));
    }

    @Bean
    IdempotentReplay idempotentReplay(IdempotencyRepository idempotency) {
        return new IdempotentReplay(idempotency);
    }

    @Bean
    CreateEventUseCase createEventUseCase(EventRepository events, IdempotentReplay idempotentReplay, Clock clock) {
        return new CreateEventUseCase(events, idempotentReplay, clock);
    }

    @Bean
    GetEventUseCase getEventUseCase(EventRepository events) {
        return new GetEventUseCase(events);
    }

    @Bean
    ListEventsUseCase listEventsUseCase(EventRepository events, Clock clock) {
        return new ListEventsUseCase(events, clock);
    }

    @Bean
    GetAvailabilityUseCase getAvailabilityUseCase(EventRepository events) {
        return new GetAvailabilityUseCase(events);
    }

    @Bean
    PlaceOrderUseCase placeOrderUseCase(EventRepository events, OrderRepository orders,
                                        IdempotentReplay idempotentReplay, Clock clock) {
        return new PlaceOrderUseCase(events, orders, idempotentReplay, clock);
    }

    @Bean
    IssueComplimentaryUseCase issueComplimentaryUseCase(EventRepository events, OrderRepository orders,
                                                        IdempotentReplay idempotentReplay, Clock clock) {
        return new IssueComplimentaryUseCase(events, orders, idempotentReplay, clock);
    }

    @Bean
    GetOrderUseCase getOrderUseCase(OrderRepository orders) {
        return new GetOrderUseCase(orders);
    }

    @Bean
    ProcessPlacedOrderUseCase processPlacedOrderUseCase(OrderRepository orders, Clock clock) {
        return new ProcessPlacedOrderUseCase(orders, clock);
    }

    @Bean
    ReleaseExpiredHoldsUseCase releaseExpiredHoldsUseCase(OrderRepository orders, Clock clock) {
        return new ReleaseExpiredHoldsUseCase(orders, clock);
    }

    @Bean
    ConfirmOrderUseCase confirmOrderUseCase(GetOrderUseCase getOrder, OrderRepository orders, Clock clock) {
        return new ConfirmOrderUseCase(getOrder, orders, clock);
    }
}
