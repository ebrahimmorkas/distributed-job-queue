package com.ebrahimmorkas.jobs.worker;

import com.ebrahimmorkas.jobs.job.Job;
import com.ebrahimmorkas.jobs.job.JobStatus;
import com.ebrahimmorkas.jobs.job.NewJob;
import com.ebrahimmorkas.jobs.support.CountingJobHandler;
import com.ebrahimmorkas.jobs.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class CrashRecoveryIntegrationTest extends IntegrationTest {

    @Autowired
    private LeaseReaper reaper;

    @Autowired
    private CountingJobHandler countingHandler;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void isolate() {
        // Park other tests' leftovers so only this test's jobs are claimable
        jdbc.sql("update jobs set status = 'CANCELLED' where status in ('QUEUED', 'RUNNING')").update();
    }

    @Test
    void jobHeldByACrashedWorkerIsRecoveredAndCompletedByAnother() {
        long id = jobRepository.insert(new NewJob(CountingJobHandler.TYPE, "{}", 0, 3, Instant.now(), null)).id();
        // "crashed-worker" claims the job with a 1s lease and then dies without finishing it
        assertThat(jobRepository.claim("crashed-worker", 1, Duration.ofSeconds(1))).hasSize(1);

        await().atMost(Duration.ofSeconds(10)).until(() -> reaper.reap() > 0 || statusOf(id) == JobStatus.QUEUED);
        Job recovered = jobRepository.findById(id).orElseThrow();
        assertThat(recovered.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(recovered.lastError()).contains("crashed-worker");

        startWorker("healthy-worker", 2);

        Job done = awaitStatus(id, JobStatus.SUCCEEDED);
        assertThat(done.attempts()).isEqualTo(2);
    }

    @Test
    void expiredLeaseOnTheLastAttemptMakesTheJobDead() {
        long id = jobRepository.insert(new NewJob(CountingJobHandler.TYPE, "{}", 0, 1, Instant.now(), null)).id();
        jobRepository.claim("crashed-worker", 1, Duration.ofSeconds(1));

        await().atMost(Duration.ofSeconds(10)).until(() -> {
            reaper.reap();
            return statusOf(id) == JobStatus.DEAD;
        });
        assertThat(jobRepository.findById(id).orElseThrow().completedAt()).isNotNull();
    }

    @Test
    void liveWorkerRenewsItsLeaseSoLongJobsAreNotStolen() throws Exception {
        long id = jobRepository.insert(new NewJob(CountingJobHandler.TYPE, "{\"sleepMs\": 5000}", 0, 3,
                Instant.now(), null)).id();
        startWorker("slow-worker", 1, Duration.ofSeconds(3));

        // The job runs for 5s with a 3s lease: without renewal the reaper would steal it
        long deadline = System.currentTimeMillis() + 6_000;
        while (System.currentTimeMillis() < deadline && statusOf(id) != JobStatus.SUCCEEDED) {
            reaper.reap();
            assertThat(statusOf(id)).isIn(JobStatus.RUNNING, JobStatus.SUCCEEDED);
            Thread.sleep(250);
        }

        Job done = awaitStatus(id, JobStatus.SUCCEEDED);
        assertThat(done.attempts()).isEqualTo(1);
        assertThat(countingHandler.executionsOf(id)).isEqualTo(1);
    }

    private JobStatus statusOf(long id) {
        return jobRepository.findById(id).orElseThrow().status();
    }
}
