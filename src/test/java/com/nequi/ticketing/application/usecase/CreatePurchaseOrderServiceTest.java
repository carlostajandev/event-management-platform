package com.nequi.ticketing.application.usecase;

import tools.jackson.databind.json.JsonMapper;
import com.nequi.ticketing.application.dto.CreatePurchaseOrderCommand;
import com.nequi.ticketing.application.port.out.MessagePublisher;
import com.nequi.ticketing.domain.model.Order;
import com.nequi.ticketing.domain.model.OrderStatus;
import com.nequi.ticketing.domain.repository.OrderRepository;
import com.nequi.ticketing.domain.valueobject.EventId;
import com.nequi.ticketing.domain.valueobject.Money;
import com.nequi.ticketing.domain.valueobject.OrderId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CreatePurchaseOrderService")
class CreatePurchaseOrderServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private MessagePublisher messagePublisher;

    private CreatePurchaseOrderService service;

    @BeforeEach
    void setUp() {
        JsonMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        service = new CreatePurchaseOrderService(orderRepository, messagePublisher, objectMapper);
    }

    @Test
    @DisplayName("should create order and publish to SQS")
    void shouldCreateOrderAndPublishToSqs() {
        CreatePurchaseOrderCommand command = validCommand();
        Order savedOrder = buildOrder(command);

        when(orderRepository.save(any())).thenReturn(Mono.just(savedOrder));
        when(messagePublisher.publishPurchaseOrder(anyString(), anyString()))
                .thenReturn(Mono.just("msg_123"));

        StepVerifier.create(service.execute(command))
                .assertNext(response -> {
                    assertThat(response.orderId()).isEqualTo(command.orderId());
                    assertThat(response.status()).isEqualTo("PENDING");
                    assertThat(response.eventId()).isEqualTo(command.eventId());
                    assertThat(response.ticketIds()).hasSize(2);
                })
                .verifyComplete();

        verify(orderRepository).save(any());
        verify(messagePublisher).publishPurchaseOrder(anyString(), anyString());
    }

    @Test
    @DisplayName("should fail when SQS publish fails")
    void shouldFailWhenSqsPublishFails() {
        CreatePurchaseOrderCommand command = validCommand();
        Order savedOrder = buildOrder(command);

        when(orderRepository.save(any())).thenReturn(Mono.just(savedOrder));
        when(messagePublisher.publishPurchaseOrder(anyString(), anyString()))
                .thenReturn(Mono.error(new RuntimeException("SQS unavailable")));

        StepVerifier.create(service.execute(command))
                .expectErrorMatches(ex -> ex.getMessage().contains("SQS unavailable"))
                .verify();
    }

    private CreatePurchaseOrderCommand validCommand() {
        return new CreatePurchaseOrderCommand(
                "ord_test123",
                "evt_abc456",
                "usr_xyz789",
                List.of("tkt_001", "tkt_002"),
                new BigDecimal("700000.00"),
                "COP"
        );
    }

    private Order buildOrder(CreatePurchaseOrderCommand command) {
        return new Order(
                OrderId.of(command.orderId()),
                EventId.of(command.eventId()),
                command.userId(),
                command.ticketIds(),
                command.ticketIds().size(),
                Money.of(command.totalAmount(), command.currency()),
                OrderStatus.PENDING,
                null,
                Instant.now(), Instant.now(), 0L
        );
    }
}