package com.wassim.reckon.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record QaRequest(
        @NotBlank
        @Size(min = 3, max = 1000, message = "question must be between 3 and 1000 characters")
        String question
) {
}
