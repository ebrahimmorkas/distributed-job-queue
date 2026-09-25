package com.ebrahimmorkas.jobs.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param enabled         run a worker in this instance (API-only instances can turn it off)
 * @param concurrency     max jobs executing at once in this instance
 * @param pollInterval    how long to wait before polling again when the queue is empty
 * @param lease           how long a claimed job is reserved for this worker
 * @param retryBaseDelay  delay before the first retry
 * @param shutdownTimeout how long a graceful shutdown waits for in-flight jobs
 */
@ConfigurationProperties("app.worker")
public record WorkerProperties(boolean enabled, int concurrency, Duration pollInterval, Duration lease,
                               Duration retryBaseDelay, Duration shutdownTimeout) {
}
