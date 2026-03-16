package com.nequi.ticketing.infrastructure.web.versioning;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an endpoint or router with its API version.
 *
 * <p>Versioning strategy: URL prefix versioning (/api/v1/, /api/v2/).
 * <ul>
 *   <li>Pros: explicit, cache-friendly, easy to route at ALB/gateway level</li>
 *   <li>Cons: URL proliferation (mitigated by keeping v1 active until deprecated)</li>
 * </ul>
 *
 * <p>Versioning policy:
 * <ul>
 *   <li>New major versions add a new router/handler pair — old version stays untouched</li>
 *   <li>A version is deprecated with a {@code Deprecation} response header before removal</li>
 *   <li>Minimum 6-month deprecation window before removing a version</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * @ApiVersion(1)
 * @Configuration
 * public class EventRouterV1 { ... }
 *
 * @ApiVersion(2)
 * @Configuration
 * public class EventRouterV2 { ... }
 * }</pre>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiVersion {

    /** API version number (1, 2, 3...). */
    int value();

    /** Whether this version is deprecated. Adds Deprecation response header. */
    boolean deprecated() default false;

    /** Human-readable deprecation message if deprecated=true. */
    String deprecationMessage() default "";
}