package com.ebrahimmorkas.jobs.metrics;

import com.ebrahimmorkas.jobs.job.Job;
import com.ebrahimmorkas.jobs.job.JobStatus;
import com.ebrahimmorkas.jobs.job.NewJob;
import com.ebrahimmorkas.jobs.support.CountingJobHandler;
import com.ebrahimmorkas.jobs.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MetricsIntegrationTest extends IntegrationTest {

    @Autowired
    private QueueMetrics queueMetrics;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void executionsAreTimedPerTypeAndOutcomeAndCompletedByIsRecorded() throws Exception {
        long id = jobRepository.insert(new NewJob(CountingJobHandler.TYPE, "{}", 0, 3, null, null)).id();
        startWorker("metrics-worker", 2);

        Job done = awaitStatus(id, JobStatus.SUCCEEDED);
        assertThat(done.completedBy()).isEqualTo("metrics-worker");

        String metrics = scrape();
        assertThat(metrics).contains("jobs_execution_seconds_count{");
        assertThat(metrics).containsPattern("jobs_completed_total\\{[^}]*outcome=\"succeeded\"[^}]*type=\"test.counting\"");
        assertThat(metrics).containsPattern("jobs_worker_busy\\{[^}]*worker=\"metrics-worker\"");
    }

    @Test
    void lagReportsHowLongTheOldestDueJobHasBeenWaiting() throws Exception {
        jdbc.sql("update jobs set status = 'CANCELLED' where status = 'QUEUED'").update();
        long id = jobRepository.insert(new NewJob(CountingJobHandler.TYPE, "{}", 0, 3, null, null)).id();
        jdbc.sql("update jobs set run_at = now() - interval '90 seconds' where id = :id").param("id", id).update();

        queueMetrics.refresh();

        String metrics = scrape();
        assertThat(gaugeValue(metrics, "jobs_queue_lag_seconds")).isGreaterThanOrEqualTo(90);
        assertThat(metrics).containsPattern("jobs_queue_depth\\{[^}]*status=\"QUEUED\"[^}]*} 1\\.0");
    }

    private String scrape() throws Exception {
        return mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static double gaugeValue(String metrics, String name) {
        Matcher matcher = Pattern.compile("(?m)^" + name + "\\{[^}]*} ([0-9.E+-]+)$").matcher(metrics);
        assertThat(matcher.find()).as("metric %s present", name).isTrue();
        return Double.parseDouble(matcher.group(1));
    }
}
