package com.kovil.common.dto.response;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

public record PageResponse<T>(
        int currentPage,
        int totalPages,
        long totalElements,
        int size,
        boolean first,
        boolean last,
        boolean hasNext,
        boolean hasPrevious,
        List<T> content
) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getNumber(),
                page.getTotalPages(),
                page.getTotalElements(),
                page.getSize(),
                page.isFirst(),
                page.isLast(),
                page.hasNext(),
                page.hasPrevious(),
                page.getContent()
        );
    }

    public <R> PageResponse<R> map(Function<T, R> mapper) {
        return new PageResponse<>(
                currentPage, totalPages, totalElements, size,
                first, last, hasNext, hasPrevious,
                content.stream().map(mapper).toList()
        );
    }
}