package com.nequi.ticketing.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * CORS configuration for the reactive WebFlux application.
 *
 * <p>In production origins are restricted to known frontends.
 * Never use {@code *} in production for a financial API.
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource(TicketingProperties properties) {
        CorsConfiguration config = new CorsConfiguration();

        // Allowed origins — override via ticketing.cors.allowed-origins in application.yml
        config.setAllowedOrigins(properties.cors().allowedOrigins());

        // Standard HTTP methods for a REST API
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));

        // Allow all standard headers + our custom headers
        config.setAllowedHeaders(List.of(
                "Content-Type",
                "Authorization",
                "X-Correlation-Id",
                "X-Idempotency-Key",
                "Accept",
                "Origin"
        ));

        // Expose our custom headers to the client
        config.setExposedHeaders(List.of(
                "X-Correlation-Id",
                "X-Idempotency-Key"
        ));

        config.setAllowCredentials(true);

        // Cache preflight response for 1 hour
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}