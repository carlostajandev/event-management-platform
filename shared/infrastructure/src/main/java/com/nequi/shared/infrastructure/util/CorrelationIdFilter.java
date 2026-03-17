package com.nequi.shared.infrastructure.util;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * WebFilter that captures X-Correlation-Id header (or generates one) and
 * propagates it in the Reactor context + MDC for structured logging.
 *
 * <p>MDC keys: {@code traceId}, logged by logstash-logback-encoder as JSON fields.
 * CloudWatch/ELK can then correlate all log lines for a single request chain.
 */
@Component
public class CorrelationIdFilter implements WebFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String CORRELATION_ID_MDC_KEY = "traceId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String correlationId = exchange.getRequest().getHeaders()
                .getFirst(CORRELATION_ID_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        exchange.getResponse().getHeaders().add(CORRELATION_ID_HEADER, correlationId);

        final String finalCorrelationId = correlationId;
        return chain.filter(exchange)
                .contextWrite(ctx -> ctx.put(CORRELATION_ID_MDC_KEY, finalCorrelationId))
                .doOnSubscribe(sub -> MDC.put(CORRELATION_ID_MDC_KEY, finalCorrelationId))
                .doFinally(sig -> MDC.remove(CORRELATION_ID_MDC_KEY));
    }
}