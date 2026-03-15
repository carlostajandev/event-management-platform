package com.nequi.ticketing.infrastructure.config;

import org.springframework.context.annotation.Configuration;

/**
 * Jackson 3 configuration.
 *
 * Spring Boot 4 auto-configures a JsonMapper bean (tools.jackson.databind.json.JsonMapper)
 * via JacksonAutoConfiguration. No additional customization is needed — the default
 * configuration handles java.time types, unknown properties, and module discovery.
 *
 * Services inject JsonMapper directly (concrete type) to avoid ambiguity with
 * the abstract ObjectMapper type in the Spring context.
 */
@Configuration
public class JacksonConfig {
    // Intentionally empty — Spring Boot 4 JacksonAutoConfiguration provides
    // all required Jackson 3 configuration out of the box.
}