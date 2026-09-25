package com.ebrahimmorkas.jobs.worker;

import java.time.Duration;

/** How long to wait before retrying a job that failed its {@code attempt}-th attempt. */
public interface RetryPolicy {

    Duration delayBeforeRetry(int attempt);

    static RetryPolicy fixed(Duration delay) {
        return attempt -> delay;
    }
}
