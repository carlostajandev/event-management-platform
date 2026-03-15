package com.nequi.ticketing.infrastructure.web.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CorrelationIdFilter")
class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    @DisplayName("should generate correlationId when header is absent")
    void shouldGenerateCorrelationIdWhenAbsent() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/v1/events")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        WebFilterChain chain = ex -> Mono.empty();

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        String correlationId = exchange.getResponse()
                .getHeaders()
                .getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER);

        assertThat(correlationId).isNotNull().isNotBlank();
    }

    @Test
    @DisplayName("should reuse correlationId from incoming header")
    void shouldReuseCorrelationIdFromHeader() {
        String existingId = "my-trace-id-123";
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/v1/events")
                .header(CorrelationIdFilter.CORRELATION_ID_HEADER, existingId)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        WebFilterChain chain = ex -> Mono.empty();

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        String correlationId = exchange.getResponse()
                .getHeaders()
                .getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER);

        assertThat(correlationId).isEqualTo(existingId);
    }
}
