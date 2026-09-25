package com.ebrahimmorkas.jobs.support;

import com.ebrahimmorkas.jobs.handler.JobHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Test handler that records how many times each job ran, to prove exactly-once processing. */
public class CountingJobHandler implements JobHandler {

    public static final String TYPE = "test.counting";

    private final Map<Long, AtomicInteger> executions = new ConcurrentHashMap<>();

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void handle(JobContext context) throws InterruptedException {
        executions.computeIfAbsent(context.jobId(), id -> new AtomicInteger()).incrementAndGet();
        Thread.sleep(context.payload().path("sleepMs").asLong(0));
    }

    public int executionsOf(long jobId) {
        AtomicInteger count = executions.get(jobId);
        return count == null ? 0 : count.get();
    }
}
