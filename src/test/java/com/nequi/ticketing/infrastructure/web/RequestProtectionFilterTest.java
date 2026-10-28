package com.nequi.ticketing.infrastructure.web;

import com.nequi.ticketing.infrastructure.config.ApiProperties;
import com.nequi.ticketing.infrastructure.config.ApiProperties.RateLimit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RequestProtectionFilterTest {

    private static final RateLimit OFF = new RateLimit(0, 1);

    private final AtomicInteger handled = new AtomicInteger();
    private final WebFilterChain passThrough = exchange -> {
        handled.incrementAndGet();
        return Mono.empty();
    };

    @Test
    void rejectsAUserAboveTheirRateWith429() {
        RequestProtectionFilter filter = new RequestProtectionFilter(new ApiProperties(10, new RateLimit(1, 2), OFF, 100));

        assertThat(status(filter, request("ana", "10.0.0.1"))).isNull();
        assertThat(status(filter, request("ana", "10.0.0.1"))).isNull();
        MockServerWebExchange limited = request("ana", "10.0.0.1");
        filter.filter(limited, passThrough).block();

        assertThat(limited.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(limited.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        StepVerifier.create(limited.getResponse().getBodyAsString())
                .assertNext(body -> assertThat(body).contains("\"code\":\"RATE_LIMITED\"").contains("\"status\":429"))
                .verifyComplete();
        assertThat(status(filter, request("luis", "10.0.0.1"))).isNull();
        assertThat(handled).hasValue(3);
    }

    @Test
    void rejectsAClientAddressAboveItsRateWhateverUserItClaims() {
        RequestProtectionFilter filter = new RequestProtectionFilter(new ApiProperties(10, OFF, new RateLimit(1, 1), 100));

        assertThat(status(filter, request("ana", "10.0.0.1"))).isNull();
        assertThat(status(filter, request("luis", "10.0.0.1"))).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(status(filter, request(null, "10.0.0.2"))).isNull();
    }

    @Test
    void shedsLoadAboveTheInFlightCapWith503AndFreesTheSlotWhenARequestEnds() {
        RequestProtectionFilter filter = new RequestProtectionFilter(new ApiProperties(1, OFF, OFF, 100));
        Sinks.Empty<Void> slow = Sinks.empty();

        MockServerWebExchange first = request("ana", "10.0.0.1");
        filter.filter(first, exchange -> slow.asMono()).subscribe();

        MockServerWebExchange shed = request("luis", "10.0.0.2");
        filter.filter(shed, passThrough).block();
        assertThat(shed.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        StepVerifier.create(shed.getResponse().getBodyAsString())
                .assertNext(body -> assertThat(body).contains("\"code\":\"OVERLOADED\""))
                .verifyComplete();

        slow.tryEmitEmpty();
        assertThat(status(filter, request("luis", "10.0.0.2"))).isNull();
    }

    @Test
    void neverLimitsHealthChecks() {
        RequestProtectionFilter filter = new RequestProtectionFilter(new ApiProperties(1, new RateLimit(1, 1), OFF, 100));

        for (int i = 0; i < 5; i++) {
            MockServerWebExchange health = MockServerWebExchange.from(MockServerHttpRequest.get("/actuator/health/readiness")
                    .header(HeaderCurrentCaller.USER_HEADER, "ana"));
            filter.filter(health, passThrough).block();
            assertThat(health.getResponse().getStatusCode()).isNull();
        }
        assertThat(handled).hasValue(5);
    }

    private HttpStatus status(RequestProtectionFilter filter, MockServerWebExchange exchange) {
        filter.filter(exchange, passThrough).block();
        return (HttpStatus) exchange.getResponse().getStatusCode();
    }

    private static MockServerWebExchange request(String user, String address) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.post("/api/v1/orders")
                .remoteAddress(new InetSocketAddress(address, 40000));
        if (user != null) {
            request.header(HeaderCurrentCaller.USER_HEADER, user);
        }
        return MockServerWebExchange.from(request);
    }
}
