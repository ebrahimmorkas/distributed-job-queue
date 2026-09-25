package com.ebrahimmorkas.jobs.handler.demo;

import com.ebrahimmorkas.jobs.handler.JobHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Demo handler simulating a call to an email provider (I/O-bound: ideal for virtual threads). */
@Slf4j
@Component
public class EmailJobHandler implements JobHandler {

    @Override
    public String type() {
        return "email.send";
    }

    @Override
    public void handle(JobContext context) throws InterruptedException {
        String to = context.payload().path("to").asText();
        if (to.isBlank()) {
            throw new IllegalArgumentException("payload.to is required");
        }
        Thread.sleep(context.payload().path("latencyMs").asLong(200));
        log.info("Sent email to {} (job {}, attempt {})", to, context.jobId(), context.attempt());
    }
}
