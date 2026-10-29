package com.nequi.ticketing.infrastructure.web;

import com.nequi.ticketing.application.event.CreateEventCommand;
import com.nequi.ticketing.application.event.CreateEventUseCase;
import com.nequi.ticketing.application.event.GetAvailabilityUseCase;
import com.nequi.ticketing.application.event.GetEventUseCase;
import com.nequi.ticketing.application.event.ListEventsUseCase;
import com.nequi.ticketing.application.order.IssueComplimentaryUseCase;
import com.nequi.ticketing.application.order.PlaceOrderCommand;
import com.nequi.ticketing.domain.model.EventId;
import com.nequi.ticketing.domain.model.IdempotencyKey;
import com.nequi.ticketing.domain.model.Money;
import com.nequi.ticketing.domain.model.UserId;
import com.nequi.ticketing.infrastructure.web.dto.AvailabilityResponse;
import com.nequi.ticketing.infrastructure.web.dto.ComplimentaryRequest;
import com.nequi.ticketing.infrastructure.web.dto.CreateEventRequest;
import com.nequi.ticketing.infrastructure.web.dto.EventResponse;
import com.nequi.ticketing.infrastructure.web.dto.OrderResponse;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;

@Profile("api")
@RestController
@RequestMapping("/api/v1/events")
class EventController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String ADMIN_SCOPE = "admin";

    private final CreateEventUseCase createEvent;
    private final GetEventUseCase getEvent;
    private final ListEventsUseCase listEvents;
    private final GetAvailabilityUseCase getAvailability;
    private final IssueComplimentaryUseCase issueComplimentary;
    private final CurrentCaller caller;

    EventController(CreateEventUseCase createEvent, GetEventUseCase getEvent, ListEventsUseCase listEvents,
                    GetAvailabilityUseCase getAvailability, IssueComplimentaryUseCase issueComplimentary,
                    CurrentCaller caller) {
        this.createEvent = createEvent;
        this.getEvent = getEvent;
        this.listEvents = listEvents;
        this.getAvailability = getAvailability;
        this.issueComplimentary = issueComplimentary;
        this.caller = caller;
    }

    @PostMapping
    Mono<ResponseEntity<EventResponse>> create(@RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
                                               @Valid @RequestBody CreateEventRequest request,
                                               ServerWebExchange exchange) {
        return caller.requireAdmin(exchange)
                .then(Mono.fromCallable(() -> new CreateEventCommand(request.name(), request.venue(), request.date(),
                        request.capacity(), Money.of(request.ticketPrice().toPlainString(), request.currency()),
                        new IdempotencyKey(ADMIN_SCOPE, idempotencyKey), RequestFingerprint.of(request.name(),
                        request.venue(), request.date(), request.capacity(), request.ticketPrice(), request.currency()))))
                .flatMap(createEvent::execute)
                .map(event -> ResponseEntity.created(URI.create("/api/v1/events/" + event.id().value()))
                        .body(EventResponse.from(event)));
    }

    @GetMapping
    Flux<EventResponse> list(@RequestParam(defaultValue = "20") int limit) {
        return listEvents.execute(limit).map(EventResponse::from);
    }

    @GetMapping("/{id}")
    Mono<EventResponse> get(@PathVariable String id) {
        return Mono.fromCallable(() -> new EventId(id)).flatMap(getEvent::execute).map(EventResponse::from);
    }

    @GetMapping("/{id}/availability")
    Mono<AvailabilityResponse> availability(@PathVariable String id) {
        return Mono.fromCallable(() -> new EventId(id)).flatMap(getAvailability::execute).map(AvailabilityResponse::from);
    }

    @PostMapping("/{id}/complimentary")
    Mono<ResponseEntity<OrderResponse>> complimentary(@PathVariable String id,
                                                      @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
                                                      @Valid @RequestBody ComplimentaryRequest request,
                                                      ServerWebExchange exchange) {
        return caller.requireAdmin(exchange)
                .then(Mono.fromCallable(() -> new PlaceOrderCommand(new EventId(id), new UserId(request.recipientUserId()),
                        request.quantity(), new IdempotencyKey(ADMIN_SCOPE, idempotencyKey),
                        RequestFingerprint.of("complimentary", id, request.recipientUserId(), request.quantity()))))
                .flatMap(issueComplimentary::execute)
                .map(order -> ResponseEntity.created(URI.create("/api/v1/orders/" + order.id().value()))
                        .body(OrderResponse.from(order)));
    }
}
