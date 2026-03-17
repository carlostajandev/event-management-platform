package com.nequi.eventservice.infrastructure.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes a JSR-380 {@link Validator} bean for manual validation in the
 * WebFlux functional handler.
 *
 * <p>Spring Boot auto-configures a validator when using {@code @Valid} in
 * MVC controllers; in the functional routing model we inject the validator
 * explicitly to validate request bodies before dispatching to use cases.
 */
@Configuration
public class ValidationConfig {

    @Bean
    public Validator validator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            return factory.getValidator();
        }
    }
}
