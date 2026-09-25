package com.ebrahimmorkas.jobs.worker;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ExponentialBackoffTest {

    private final ExponentialBackoff backoff = new ExponentialBackoff(Duration.ofSeconds(10), Duration.ofMinutes(5));

    @RepeatedTest(20)
    void delayDoublesPerAttemptWithinJitterBounds() {
        assertThat(backoff.delayBeforeRetry(1)).isBetween(Duration.ofSeconds(5), Duration.ofSeconds(10));
        assertThat(backoff.delayBeforeRetry(2)).isBetween(Duration.ofSeconds(10), Duration.ofSeconds(20));
        assertThat(backoff.delayBeforeRetry(3)).isBetween(Duration.ofSeconds(20), Duration.ofSeconds(40));
    }

    @RepeatedTest(20)
    void delayIsCappedEvenForHugeAttemptNumbers() {
        assertThat(backoff.delayBeforeRetry(10)).isBetween(Duration.ofSeconds(150), Duration.ofMinutes(5));
        assertThat(backoff.delayBeforeRetry(1_000)).isLessThanOrEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void jitterSpreadsRetriesOfJobsThatFailedTogether() {
        Set<Duration> delays = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            delays.add(backoff.delayBeforeRetry(3));
        }
        assertThat(delays).hasSizeGreaterThan(40);
    }
}
