package com.nequi.ticketing.infrastructure.web;

import com.nequi.ticketing.domain.exception.DomainValidationException;
import com.nequi.ticketing.domain.model.UserId;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Local-mode identity from headers. Not authentication: any client can set them (declared limitation, ADR 0003). */
@Component
class HeaderCurrentCaller implements CurrentCaller {

    static final String USER_HEADER = "X-User-Id";
    static final String ROLE_HEADER = "X-User-Role";
    private static final String ADMIN = "admin";

    @Override
    public Mono<UserId> user(ServerWebExchange exchange) {
        String header = exchange.getRequest().getHeaders().getFirst(USER_HEADER);
        if (header == null || header.isBlank()) {
            return Mono.error(new CallerNotIdentifiedException());
        }
        try {
            return Mono.just(new UserId(header.strip()));
        } catch (DomainValidationException e) {
            return Mono.error(e);
        }
    }

    @Override
    public Mono<Void> requireAdmin(ServerWebExchange exchange) {
        return ADMIN.equals(exchange.getRequest().getHeaders().getFirst(ROLE_HEADER))
                ? Mono.empty()
                : Mono.error(new ForbiddenException());
    }
}
