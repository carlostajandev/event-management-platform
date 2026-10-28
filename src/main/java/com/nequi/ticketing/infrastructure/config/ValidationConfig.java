package com.nequi.ticketing.infrastructure.config;

import jakarta.validation.MessageInterpolator;
import org.springframework.boot.validation.autoconfigure.ValidationConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;

@Configuration(proxyBeanMethods = false)
public class ValidationConfig {

    /**
     * Validation messages are part of the API, so they are English whatever the JVM's default locale. Spring would
     * otherwise interpolate them in the locale of the machine (there is no per-request locale in this API).
     */
    @Bean
    ValidationConfigurationCustomizer englishValidationMessages() {
        return configuration -> configuration.messageInterpolator(
                new FixedLocaleInterpolator(configuration.getDefaultMessageInterpolator(), Locale.ENGLISH));
    }

    private record FixedLocaleInterpolator(MessageInterpolator target, Locale locale) implements MessageInterpolator {

        @Override
        public String interpolate(String template, Context context) {
            return target.interpolate(template, context, locale);
        }

        @Override
        public String interpolate(String template, Context context, Locale ignored) {
            return target.interpolate(template, context, locale);
        }
    }
}
