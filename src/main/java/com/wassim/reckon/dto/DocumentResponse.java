package com.wassim.reckon.dto;

import com.wassim.reckon.model.Document;

import java.time.Instant;
import java.util.UUID;

public record DocumentResponse(
        UUID id,
        String filename,
        int chunkCount,
        Instant ingestedAt
) {
    public static DocumentResponse from(Document document) {
        return new DocumentResponse(
                document.getId(),
                document.getFilename(),
                document.getChunkCount(),
                document.getIngestedAt());
    }
}
