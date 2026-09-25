package com.ebrahimmorkas.jobs.api;

import com.ebrahimmorkas.jobs.job.Job;
import com.ebrahimmorkas.jobs.job.JobStatus;
import com.fasterxml.jackson.annotation.JsonRawValue;

import java.time.Instant;

public record JobResponse(
        long id,
        String type,
        @JsonRawValue String payload,
        JobStatus status,
        int priority,
        int attempts,
        int maxAttempts,
        Instant runAt,
        String lastError,
        String idempotencyKey,
        Instant createdAt,
        Instant completedAt) {

    static JobResponse from(Job job) {
        return new JobResponse(job.id(), job.type(), job.payload(), job.status(), job.priority(), job.attempts(),
                job.maxAttempts(), job.runAt(), job.lastError(), job.idempotencyKey(), job.createdAt(),
                job.completedAt());
    }
}
