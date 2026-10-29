package com.nequi.ticketing.infrastructure.web;

import com.nequi.ticketing.domain.model.UserId;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Resolves who is calling. Authentication is out of scope (ADR 0003): this port is the only place that knows
 * how identity is carried, so a JWT-based implementation replaces it without touching controllers or use cases.
 */
public interface CurrentCaller {

    Mono<UserId> user(ServerWebExchange exchange);

    /** Completes empty when the caller is an admin; fails with {@link ForbiddenException} otherwise. */
    Mono<Void> requireAdmin(ServerWebExchange exchange);
}
