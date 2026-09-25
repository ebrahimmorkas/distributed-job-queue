package com.ebrahimmorkas.jobs.job;

public enum JobStatus {
    /** Waiting to run (possibly in the future, e.g. after a retry backoff). */
    QUEUED,
    /** Claimed by a worker holding a lease. */
    RUNNING,
    SUCCEEDED,
    /** Failed on every allowed attempt; needs a human (can be retried manually). */
    DEAD,
    CANCELLED
}
