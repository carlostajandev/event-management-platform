package com.nequi.orderservice.infrastructure.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides a Jakarta Bean Validation {@link Validator} bean for use in WebFlux handlers.
 *
 * <p>WebFlux functional handlers do not automatically invoke {@code @Valid} annotation
 * processing — validation must be triggered manually. This bean is injected into
 * handlers that need programmatic validation.
 */
@Configuration
public class ValidationConfig {

    @Bean
    public Validator validator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        return factory.getValidator();
    }
}
