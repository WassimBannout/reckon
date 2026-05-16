package com.wassim.reckon.dto;

import java.util.List;

public record QaResponse(
        String answer,
        List<Citation> citations
) {
}
