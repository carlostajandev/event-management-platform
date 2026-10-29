package com.nequi.ticketing.application.event;

import com.nequi.ticketing.application.idempotency.IdempotentReplay;
import com.nequi.ticketing.domain.exception.DomainValidationException;
import com.nequi.ticketing.domain.exception.NotFoundException;
import com.nequi.ticketing.domain.model.Event;
import com.nequi.ticketing.domain.model.EventId;
import com.nequi.ticketing.domain.model.IdempotencyRecord;
import com.nequi.ticketing.domain.model.Inventory;
import com.nequi.ticketing.domain.model.Money;
import com.nequi.ticketing.domain.port.EventRepository;
import com.nequi.ticketing.domain.port.IdempotencyRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;

import static com.nequi.ticketing.application.Fixtures.CLOCK;
import static com.nequi.ticketing.application.Fixtures.KEY;
import static com.nequi.ticketing.application.Fixtures.NOW;
import static com.nequi.ticketing.application.Fixtures.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EventUseCasesTest {

    private final EventRepository events = mock(EventRepository.class);
    private final IdempotencyRepository idempotency = mock(IdempotencyRepository.class);
    private final CreateEventUseCase createEvent = new CreateEventUseCase(events, new IdempotentReplay(idempotency), CLOCK);

    @Test
    void createsTheEventWithAFullInventoryAndItsIdempotencyRecord() {
        when(idempotency.find(KEY)).thenReturn(Mono.empty());
        when(events.create(any(), any(), any())).thenAnswer(call -> Mono.just(call.getArgument(0)));

        StepVerifier.create(createEvent.execute(command(Duration.ofDays(30))))
                .assertNext(event -> assertThat(event.capacity()).isEqualTo(500))
                .verifyComplete();

        ArgumentCaptor<Event> event = ArgumentCaptor.forClass(Event.class);
        ArgumentCaptor<Inventory> inventory = ArgumentCaptor.forClass(Inventory.class);
        ArgumentCaptor<IdempotencyRecord> record = ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(events).create(event.capture(), inventory.capture(), record.capture());
        assertThat(inventory.getValue()).isEqualTo(Inventory.initial(event.getValue().id(), 500));
        assertThat(record.getValue().resourceId()).isEqualTo(event.getValue().id().value());
        assertThat(record.getValue().createdAt()).isEqualTo(NOW);
    }

    @Test
    void aReplayReturnsTheOriginalEvent() {
        Event original = event();
        when(idempotency.find(KEY)).thenReturn(Mono.just(new IdempotencyRecord(KEY, "hash", original.id().value(), NOW)));
        when(events.findById(original.id())).thenReturn(Mono.just(original));

        StepVerifier.create(createEvent.execute(command(Duration.ofDays(30)))).expectNext(original).verifyComplete();
    }

    @Test
    void aReplayOfAMissingEventIsNotFound() {
        when(idempotency.find(KEY)).thenReturn(Mono.just(new IdempotencyRecord(KEY, "hash", "gone", NOW)));
        when(events.findById(new EventId("gone"))).thenReturn(Mono.empty());

        StepVerifier.create(createEvent.execute(command(Duration.ofDays(30)))).expectError(NotFoundException.class).verify();
    }

    @Test
    void rejectsInvalidEventsBeforeWriting() {
        when(idempotency.find(KEY)).thenReturn(Mono.empty());

        StepVerifier.create(createEvent.execute(command(Duration.ofDays(-1)))).expectError(DomainValidationException.class).verify();
        verify(events, never()).create(any(), any(), any());
    }

    @Test
    void queriesFailWithNotFoundForUnknownEvents() {
        EventId unknown = new EventId("unknown");
        when(events.findById(unknown)).thenReturn(Mono.empty());
        when(events.findInventory(unknown)).thenReturn(Mono.empty());

        StepVerifier.create(new GetEventUseCase(events).execute(unknown)).expectError(NotFoundException.class).verify();
        StepVerifier.create(new GetAvailabilityUseCase(events).execute(unknown)).expectError(NotFoundException.class).verify();
    }

    @Test
    void returnsEventsAndAvailability() {
        Event event = event();
        Inventory inventory = Inventory.initial(event.id(), 100);
        when(events.findById(event.id())).thenReturn(Mono.just(event));
        when(events.findInventory(event.id())).thenReturn(Mono.just(inventory));

        StepVerifier.create(new GetEventUseCase(events).execute(event.id())).expectNext(event).verifyComplete();
        StepVerifier.create(new GetAvailabilityUseCase(events).execute(event.id())).expectNext(inventory).verifyComplete();
    }

    @Test
    void listsUpcomingEventsWithAClampedLimit() {
        ListEventsUseCase listEvents = new ListEventsUseCase(events, CLOCK);
        when(events.findUpcoming(any(Instant.class), anyInt())).thenReturn(Flux.just(event()));

        StepVerifier.create(listEvents.execute(1000)).expectNextCount(1).verifyComplete();
        StepVerifier.create(listEvents.execute(0)).expectNextCount(1).verifyComplete();

        verify(events).findUpcoming(NOW, ListEventsUseCase.MAX_LIMIT);
        verify(events).findUpcoming(NOW, 1);
        verifyNoInteractions(idempotency);
    }

    private static CreateEventCommand command(Duration fromNow) {
        return new CreateEventCommand("Rock Fest", "Movistar Arena", NOW.plus(fromNow), 500, Money.of("150000", "COP"),
                KEY, "hash");
    }
}
