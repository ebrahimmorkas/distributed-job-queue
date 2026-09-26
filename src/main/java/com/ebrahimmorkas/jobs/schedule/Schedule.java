package com.ebrahimmorkas.jobs.schedule;

import com.fasterxml.jackson.annotation.JsonRawValue;

import java.time.Instant;

public record Schedule(
        String name,
        String cron,
        String jobType,
        @JsonRawValue String payload,
        int priority,
        boolean enabled,
        Instant nextRunAt,
        Instant lastEnqueuedAt,
        Instant createdAt) {
}
