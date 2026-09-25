package com.ebrahimmorkas.jobs.handler;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Business logic for one job type. Implementations must be idempotent: delivery is
 * at-least-once, so a job may run again if a worker dies after doing the work but before
 * recording success.
 */
public interface JobHandler {

    /** The job type this handler processes, e.g. {@code "email.send"}. */
    String type();

    /** Throwing any exception marks the attempt as failed (and schedules a retry if attempts remain). */
    void handle(JobContext context) throws Exception;

    record JobContext(long jobId, int attempt, JsonNode payload) {
    }
}
