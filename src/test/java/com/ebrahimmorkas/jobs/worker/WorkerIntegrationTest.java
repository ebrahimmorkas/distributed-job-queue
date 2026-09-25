package com.ebrahimmorkas.jobs.worker;

import com.ebrahimmorkas.jobs.job.Job;
import com.ebrahimmorkas.jobs.job.JobStatus;
import com.ebrahimmorkas.jobs.job.NewJob;
import com.ebrahimmorkas.jobs.support.CountingJobHandler;
import com.ebrahimmorkas.jobs.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkerIntegrationTest extends IntegrationTest {

    @Autowired
    private CountingJobHandler countingHandler;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void jobIsExecutedAndMarkedSucceeded() throws Exception {
        long id = submit("{\"type\": \"email.send\", \"payload\": {\"to\": \"jane@example.com\", \"latencyMs\": 10}}")
                .get("id").asLong();
        startWorker("w1", 5);

        Job job = awaitStatus(id, JobStatus.SUCCEEDED);
        assertThat(job.attempts()).isEqualTo(1);
        assertThat(job.completedAt()).isNotNull();
        assertThat(job.lockedBy()).isNull();
    }

    @Test
    void failingJobIsRetriedUntilItSucceeds() throws Exception {
        long id = submit("{\"type\": \"demo.flaky\", \"payload\": {\"failTimes\": 2}, \"maxAttempts\": 5}")
                .get("id").asLong();
        startWorker("w1", 5);

        Job job = awaitStatus(id, JobStatus.SUCCEEDED);
        assertThat(job.attempts()).isEqualTo(3);
    }

    @Test
    void jobThatKeepsFailingBecomesDeadAndCanBeRetriedManually() throws Exception {
        long id = submit("{\"type\": \"demo.flaky\", \"payload\": {\"failTimes\": 99}, \"maxAttempts\": 2}")
                .get("id").asLong();
        startWorker("w1", 5);

        Job dead = awaitStatus(id, JobStatus.DEAD);
        assertThat(dead.attempts()).isEqualTo(2);
        assertThat(dead.lastError()).contains("Simulated failure on attempt 2");

        mockMvc.perform(post("/api/jobs/{id}/retry", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempts").value(0));
        assertThat(awaitStatus(id, JobStatus.DEAD).attempts()).isEqualTo(2);
    }

    @Test
    void competingWorkersProcessEveryJobExactlyOnce() {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            ids.add(jobRepository.insert(new NewJob(CountingJobHandler.TYPE, "{\"sleepMs\": 5}", 0, 3,
                    Instant.now(), null)).id());
        }

        startWorker("worker-a", 10);
        startWorker("worker-b", 10);
        startWorker("worker-c", 10);

        await().atMost(Duration.ofSeconds(60)).until(() -> ids.stream()
                .allMatch(id -> jobRepository.findById(id).orElseThrow().status() == JobStatus.SUCCEEDED));
        assertThat(ids).allSatisfy(id -> assertThat(countingHandler.executionsOf(id)).isEqualTo(1));
        assertThat(ids).allSatisfy(id -> assertThat(jobRepository.findById(id).orElseThrow().attempts()).isEqualTo(1));
    }

    @Test
    void higherPriorityAndDueJobsAreClaimedFirst() {
        // Push everything else out of the way so the ordering assertion is unambiguous
        jdbc.sql("update jobs set status = 'CANCELLED' where status = 'QUEUED'").update();
        Instant now = Instant.now();
        jobRepository.insert(new NewJob(CountingJobHandler.TYPE, "{}", 1, 3, now, null));
        long urgent = jobRepository.insert(new NewJob(CountingJobHandler.TYPE, "{}", 10, 3, now, null)).id();
        jobRepository.insert(new NewJob(CountingJobHandler.TYPE, "{}", 50, 3, now.plusSeconds(3600), null));

        List<Job> claimed = jobRepository.claim("test", 1, Duration.ofMinutes(1));

        assertThat(claimed).extracting(Job::id).containsExactly(urgent);
        assertThat(claimed.getFirst().status()).isEqualTo(JobStatus.RUNNING);
        assertThat(claimed.getFirst().lockedBy()).isEqualTo("test");
    }

    @Test
    void staleWorkerCannotCompleteAJobItNoLongerOwns() {
        jdbc.sql("update jobs set status = 'CANCELLED' where status = 'QUEUED'").update();
        long id = jobRepository.insert(new NewJob(CountingJobHandler.TYPE, "{}", 0, 3, Instant.now(), null)).id();
        jobRepository.claim("old-worker", 1, Duration.ofMinutes(1));
        // Simulate the lease having been reassigned to another worker
        jdbc.sql("update jobs set locked_by = 'new-worker' where id = :id").param("id", id).update();

        assertThat(jobRepository.markSucceeded(id, "old-worker")).isFalse();
        assertThat(jobRepository.findById(id).orElseThrow().status()).isEqualTo(JobStatus.RUNNING);
        assertThat(jobRepository.markSucceeded(id, "new-worker")).isTrue();
    }
}
