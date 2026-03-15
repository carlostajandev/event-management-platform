package com.nequi.ticketing.infrastructure.web.filter;

import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Reactive WebFilter that ensures every request has a correlation ID
 * propagated through MDC for structured logging.
 *
 * <p>Priority: first in the filter chain (Order -1).
 *
 * <p>The correlation ID is:
 * <ol>
 *   <li>Taken from the incoming {@code X-Correlation-Id} header if present</li>
 *   <li>Generated as a new UUID if not present</li>
 * </ol>
 *
 * <p>The ID is:
 * <ul>
 *   <li>Set in the reactive context for downstream access</li>
 *   <li>Added to MDC so all log statements include {@code correlationId}</li>
 *   <li>Returned in the response as {@code X-Correlation-Id}</li>
 * </ul>
 */
@Component
@Order(-1)
public class CorrelationIdFilter implements WebFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String CORRELATION_ID_MDC_KEY = "correlationId";
    public static final String REQUEST_PATH_MDC_KEY = "requestPath";
    public static final String REQUEST_METHOD_MDC_KEY = "requestMethod";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String correlationId = exchange.getRequest()
                .getHeaders()
                .getFirst(CORRELATION_ID_HEADER);

        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        final String finalCorrelationId = correlationId;

        // Add correlation ID to response headers
        exchange.getResponse()
                .getHeaders()
                .add(CORRELATION_ID_HEADER, finalCorrelationId);

        // Propagate through reactive context and MDC
        return chain.filter(exchange)
                .contextWrite(ctx -> ctx
                        .put(CORRELATION_ID_MDC_KEY, finalCorrelationId)
                        .put(REQUEST_PATH_MDC_KEY,
                                exchange.getRequest().getPath().value())
                        .put(REQUEST_METHOD_MDC_KEY,
                                exchange.getRequest().getMethod().name()))
                .doOnSubscribe(s -> {
                    MDC.put(CORRELATION_ID_MDC_KEY, finalCorrelationId);
                    MDC.put(REQUEST_PATH_MDC_KEY,
                            exchange.getRequest().getPath().value());
                    MDC.put(REQUEST_METHOD_MDC_KEY,
                            exchange.getRequest().getMethod().name());
                })
                .doFinally(s -> {
                    MDC.remove(CORRELATION_ID_MDC_KEY);
                    MDC.remove(REQUEST_PATH_MDC_KEY);
                    MDC.remove(REQUEST_METHOD_MDC_KEY);
                });
    }
}
