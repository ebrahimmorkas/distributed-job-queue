package com.ebrahimmorkas.jobs.worker;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.concurrent.Semaphore;

/** Per-worker instrumentation: execution time and outcome per job type, plus saturation. */
public class WorkerMetrics {

    private final MeterRegistry registry;

    public WorkerMetrics(MeterRegistry registry, String workerId, Semaphore slots, int concurrency) {
        this.registry = registry;
        Gauge.builder("jobs.worker.busy", slots, s -> concurrency - s.availablePermits())
                .tag("worker", workerId)
                .description("Jobs currently executing on this worker")
                .register(registry);
    }

    public void recordExecution(String type, String outcome, Duration duration) {
        Timer.builder("jobs.execution")
                .tag("type", type)
                .tag("outcome", outcome)
                .description("Time spent executing a job attempt")
                .publishPercentileHistogram()
                .register(registry)
                .record(duration);
        Counter.builder("jobs.completed")
                .tag("type", type)
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }
}
