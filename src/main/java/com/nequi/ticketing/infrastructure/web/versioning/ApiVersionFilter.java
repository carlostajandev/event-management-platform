package com.nequi.ticketing.infrastructure.web.versioning;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Adds API versioning headers to all responses.
 *
 * <p>Response headers added:
 * <ul>
 *   <li>{@code X-API-Version}: current active API version</li>
 *   <li>{@code Deprecation}: RFC 8594 deprecation header when version is deprecated</li>
 *   <li>{@code Sunset}: RFC 8594 sunset date when version will be removed</li>
 * </ul>
 *
 * <p>Clients should monitor the {@code Deprecation} header to migrate
 * before the {@code Sunset} date.
 */
@Component
public class ApiVersionFilter implements WebFilter {

    static final String CURRENT_VERSION = "v1";
    static final String HEADER_API_VERSION = "X-API-Version";
    static final String HEADER_DEPRECATION = "Deprecation";
    static final String HEADER_SUNSET = "Sunset";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        // Only add versioning headers to /api/* endpoints
        if (path.startsWith("/api/")) {
            exchange.getResponse().getHeaders()
                    .add(HEADER_API_VERSION, CURRENT_VERSION);

            // When v1 is deprecated in the future, add:
            // exchange.getResponse().getHeaders().add(HEADER_DEPRECATION, "true");
            // exchange.getResponse().getHeaders().add(HEADER_SUNSET, "2028-01-01");
        }

        return chain.filter(exchange);
    }
}