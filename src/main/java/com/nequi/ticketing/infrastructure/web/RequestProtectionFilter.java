package com.nequi.ticketing.infrastructure.web;

import com.nequi.ticketing.infrastructure.config.ApiProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Backpressure at the edge (ADR 0004). Runs before any handler, so a rejected request never reaches DynamoDB and
 * its outcome is certain: nothing was written, and the client can retry safely.
 *
 * <ol>
 *   <li>Rate limits per X-User-Id and per client address: {@code 429 RATE_LIMITED}.</li>
 *   <li>A cap on requests in flight in this instance: {@code 503 OVERLOADED}. When the datastore slows down,
 *       requests are refused at once instead of queueing until they time out with an unknown outcome.</li>
 * </ol>
 * Only {@code /api/**} is protected; health checks always get through.
 */
@Component
@Profile("api")
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestProtectionFilter implements WebFilter {

    private static final String API_PATH = "/api/";

    private final int maxInFlight;
    private final RateLimiter perUser;
    private final RateLimiter perClientIp;
    private final AtomicInteger inFlight = new AtomicInteger();

    RequestProtectionFilter(ApiProperties properties) {
        this.maxInFlight = properties.maxInFlightRequests();
        this.perUser = new RateLimiter(properties.perUser(), properties.maxTrackedClients(), System::nanoTime);
        this.perClientIp = new RateLimiter(properties.perClientIp(), properties.maxTrackedClients(), System::nanoTime);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!exchange.getRequest().getPath().value().startsWith(API_PATH)) {
            return chain.filter(exchange);
        }
        if (!withinRateLimits(exchange)) {
            return reject(exchange.getResponse(), HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Too many requests; slow down and retry later");
        }
        if (inFlight.incrementAndGet() > maxInFlight) {
            inFlight.decrementAndGet();
            return reject(exchange.getResponse(), HttpStatus.SERVICE_UNAVAILABLE, "OVERLOADED",
                    "The service is at capacity and did not process the request; retry shortly");
        }
        return chain.filter(exchange).doFinally(signal -> inFlight.decrementAndGet());
    }

    private boolean withinRateLimits(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        if (remote != null && !perClientIp.tryAcquire(remote.getAddress().getHostAddress())) {
            return false;
        }
        String user = exchange.getRequest().getHeaders().getFirst(HeaderCurrentCaller.USER_HEADER);
        return user == null || perUser.tryAcquire(user);
    }

    /** Fixed problem documents: no request data is echoed back, so a static body is safe. */
    private static Mono<Void> reject(ServerHttpResponse response, HttpStatus status, String code, String detail) {
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        response.getHeaders().set(HttpHeaders.RETRY_AFTER, "1");
        String body = """
                {"type":"about:blank","title":"%s","status":%d,"detail":"%s","code":"%s"}"""
                .formatted(status.getReasonPhrase(), status.value(), detail, code);
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}
