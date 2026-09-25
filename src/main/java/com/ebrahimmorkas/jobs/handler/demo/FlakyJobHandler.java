package com.ebrahimmorkas.jobs.handler.demo;

import com.ebrahimmorkas.jobs.handler.JobHandler;
import org.springframework.stereotype.Component;

/**
 * Demo handler that fails its first {@code payload.failTimes} attempts, to show retries with
 * backoff and dead-lettering.
 */
@Component
public class FlakyJobHandler implements JobHandler {

    @Override
    public String type() {
        return "demo.flaky";
    }

    @Override
    public void handle(JobContext context) {
        int failTimes = context.payload().path("failTimes").asInt(1);
        if (context.attempt() <= failTimes) {
            throw new IllegalStateException("Simulated failure on attempt " + context.attempt());
        }
    }
}
