package com.ebrahimmorkas.jobs.support;

import com.ebrahimmorkas.jobs.handler.JobHandlerRegistry;
import com.ebrahimmorkas.jobs.job.Job;
import com.ebrahimmorkas.jobs.job.JobRepository;
import com.ebrahimmorkas.jobs.job.JobStatus;
import com.ebrahimmorkas.jobs.worker.JobWorker;
import com.ebrahimmorkas.jobs.worker.RetryPolicy;
import com.ebrahimmorkas.jobs.worker.WorkerProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full application against a real PostgreSQL. The built-in worker is disabled so tests control
 * exactly which workers run (and how many compete) via {@link #startWorker}.
 */
@SpringBootTest(properties = {"app.worker.enabled=false", "app.worker.reaper-interval=1h"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestJobHandlersConfiguration.class})
public abstract class IntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JobRepository jobRepository;

    @Autowired
    private JobHandlerRegistry handlerRegistry;

    @Autowired
    private Clock clock;

    private final List<JobWorker> workers = new ArrayList<>();

    protected JsonNode submit(String json) throws Exception {
        String body = mockMvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    protected JobWorker startWorker(String id, int concurrency) {
        return startWorker(id, concurrency, Duration.ofMinutes(5));
    }

    protected JobWorker startWorker(String id, int concurrency, Duration lease) {
        WorkerProperties properties = new WorkerProperties(true, concurrency, Duration.ofMillis(50),
                lease, Duration.ofMillis(100), Duration.ofSeconds(1), Duration.ofSeconds(5));
        JobWorker worker = new JobWorker(id, jobRepository, handlerRegistry, RetryPolicy.fixed(Duration.ofMillis(100)),
                objectMapper, properties, clock);
        worker.start();
        workers.add(worker);
        return worker;
    }

    protected Job awaitStatus(long jobId, JobStatus expected) {
        await().atMost(Duration.ofSeconds(30)).until(() -> jobRepository.findById(jobId).orElseThrow().status() == expected);
        return jobRepository.findById(jobId).orElseThrow();
    }

    @AfterEach
    void stopWorkers() {
        workers.forEach(JobWorker::stop);
        workers.clear();
    }
}
