package com.wassim.reckon.dto;

public record Citation(
        int index,
        String documentId,
        String filename,
        Integer pageNumber,
        String excerpt
) {
}
