package com.nequi.ticketing.shared.logging;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * WebFilter that injects a unique traceId into the MDC context for every request.
 *
 * <p>This enables log correlation: every log line emitted during a request
 * automatically includes the same traceId, making it trivial to trace a full
 * purchase flow across controller → use case → repository logs.
 *
 * <p>If the caller provides an {@code X-Trace-Id} header (e.g. from an API Gateway),
 * that value is used instead of generating a new one.
 *
 * <p>The traceId is also added to the response headers for client-side debugging.
 */
@Component
public class MdcContextFilter implements WebFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String TRACE_ID_MDC_KEY = "traceId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String traceId = exchange.getRequest()
                .getHeaders()
                .getFirst(TRACE_ID_HEADER);

        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }

        exchange.getResponse().getHeaders().add(TRACE_ID_HEADER, traceId);

        String finalTraceId = traceId;
        return chain.filter(exchange)
                .contextWrite(ctx -> ctx.put(TRACE_ID_MDC_KEY, finalTraceId))
                .doOnSubscribe(s -> MDC.put(TRACE_ID_MDC_KEY, finalTraceId))
                .doFinally(signal -> MDC.remove(TRACE_ID_MDC_KEY));
    }
}
