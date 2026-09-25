package com.ebrahimmorkas.jobs.worker;

import com.ebrahimmorkas.jobs.handler.JobHandlerRegistry;
import com.ebrahimmorkas.jobs.job.JobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkerProperties.class)
public class WorkerConfig {

    @Bean
    RetryPolicy retryPolicy(WorkerProperties properties) {
        return RetryPolicy.fixed(properties.retryBaseDelay());
    }

    /** One worker per application instance; its id (host + random suffix) shows up in locked_by. */
    @Bean
    @ConditionalOnProperty(name = "app.worker.enabled", havingValue = "true", matchIfMissing = true)
    JobWorker jobWorker(JobRepository jobRepository, JobHandlerRegistry handlerRegistry, RetryPolicy retryPolicy,
                        ObjectMapper objectMapper, WorkerProperties properties, Clock clock) {
        return new JobWorker(workerId(), jobRepository, handlerRegistry, retryPolicy, objectMapper, properties, clock);
    }

    private static String workerId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "worker";
        }
        return host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
