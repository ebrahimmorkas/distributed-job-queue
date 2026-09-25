package com.ebrahimmorkas.jobs.worker;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff with "equal jitter": the delay doubles per attempt (capped), and half of it
 * is randomised. Without jitter, jobs that failed together (e.g. during a downstream outage) retry
 * together and hit the recovering service in synchronized waves.
 */
public record ExponentialBackoff(Duration base, Duration max) implements RetryPolicy {

    @Override
    public Duration delayBeforeRetry(int attempt) {
        long exponential = base.toMillis() * (1L << Math.min(Math.max(attempt - 1, 0), 30));
        long capped = Math.min(exponential, max.toMillis());
        long half = capped / 2;
        return Duration.ofMillis(half + ThreadLocalRandom.current().nextLong(half + 1));
    }
}
