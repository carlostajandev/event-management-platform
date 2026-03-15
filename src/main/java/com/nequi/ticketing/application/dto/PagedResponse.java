package com.nequi.ticketing.application.dto;

import java.util.List;

/**
 * Generic paginated response wrapper.
 *
 * @param items    The page items
 * @param page     Current page number (0-based)
 * @param size     Page size requested
 * @param hasMore  Whether there are more pages
 */
public record PagedResponse<T>(
        List<T> items,
        int page,
        int size,
        boolean hasMore
) {}
