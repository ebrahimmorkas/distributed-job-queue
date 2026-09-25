package com.ebrahimmorkas.jobs.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record SubmitJobRequest(
        @NotBlank @Size(max = 100) @Schema(example = "email.send") String type,
        @Schema(example = "{\"to\": \"jane@example.com\"}") JsonNode payload,
        @Min(-100) @Max(100) @Schema(description = "Higher runs first (default 0)") Integer priority,
        @Min(1) @Max(25) @Schema(description = "Default 5") Integer maxAttempts,
        @Schema(description = "Run no earlier than this instant (delayed jobs)") Instant runAt,
        @Size(max = 200) @Schema(description = "Submitting the same key twice returns the original job") String idempotencyKey) {
}
