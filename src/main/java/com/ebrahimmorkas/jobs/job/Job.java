package com.ebrahimmorkas.jobs.job;

import java.time.Instant;

public record Job(
        long id,
        String type,
        String payload,
        JobStatus status,
        int priority,
        int attempts,
        int maxAttempts,
        Instant runAt,
        String lockedBy,
        Instant lockedUntil,
        String lastError,
        String idempotencyKey,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {
}
