package com.ebrahimmorkas.jobs.schedule;

import com.ebrahimmorkas.jobs.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CronSchedulerIntegrationTest extends IntegrationTest {

    @Autowired
    private CronScheduler scheduler;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void dueScheduleEnqueuesAJobAndAdvancesToTheNextSlot() throws Exception {
        String name = uniqueName();
        createSchedule(name, "0 0 * * * *").andExpect(status().isCreated());
        makeDue(name, "5 seconds");

        scheduler.tick();

        assertThat(jobsFor(name)).isEqualTo(1);
        assertThat(nextRunAt(name)).isAfter(databaseNow());
    }

    @Test
    void concurrentTicksFromManyInstancesEnqueueEachSlotExactlyOnce() throws Exception {
        String name = uniqueName();
        createSchedule(name, "0 0 * * * *").andExpect(status().isCreated());
        makeDue(name, "5 seconds");

        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 8; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return scheduler.tick();
                }));
            }
            start.countDown();
            for (Future<Integer> result : results) {
                result.get();
            }
        }

        assertThat(jobsFor(name)).isEqualTo(1);
    }

    @Test
    void longDowntimeFiresOnceInsteadOfFloodingTheQueue() throws Exception {
        String name = uniqueName();
        createSchedule(name, "0 * * * * *").andExpect(status().isCreated());
        // Missed ~3 hours of every-minute slots
        makeDue(name, "3 hours");

        scheduler.tick();
        scheduler.tick();

        assertThat(jobsFor(name)).isEqualTo(1);
    }

    @Test
    void pausedSchedulesDoNotFire() throws Exception {
        String name = uniqueName();
        createSchedule(name, "0 0 * * * *").andExpect(status().isCreated());
        mockMvc.perform(post("/api/schedules/{name}/pause", name)).andExpect(jsonPath("$.enabled").value(false));
        makeDue(name, "5 seconds");

        scheduler.tick();

        assertThat(jobsFor(name)).isZero();
    }

    @Test
    void invalidSchedulesAreRejected() throws Exception {
        createSchedule(uniqueName(), "every day").andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/schedules").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"%s\", \"cron\": \"0 0 * * * *\", \"type\": \"nope\"}".formatted(uniqueName())))
                .andExpect(status().isBadRequest());

        String name = uniqueName();
        createSchedule(name, "0 0 * * * *").andExpect(status().isCreated());
        createSchedule(name, "0 0 * * * *").andExpect(status().isConflict());
    }

    private ResultActions createSchedule(String name, String cron) throws Exception {
        return mockMvc.perform(post("/api/schedules").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"%s\", \"cron\": \"%s\", \"type\": \"email.send\", \"payload\": {\"to\": \"ops@example.com\"}}"
                        .formatted(name, cron)));
    }

    private void makeDue(String name, String overdueBy) {
        jdbc.sql("update schedules set next_run_at = now() - cast(:overdue as interval) where name = :name")
                .param("overdue", overdueBy).param("name", name).update();
    }

    private long jobsFor(String name) {
        return jdbc.sql("select count(*) from jobs where idempotency_key like :prefix")
                .param("prefix", "schedule:" + name + ":%").query(Long.class).single();
    }

    private Instant databaseNow() {
        return jdbc.sql("select now()").query(Timestamp.class).single().toInstant();
    }

    private Instant nextRunAt(String name) {
        return jdbc.sql("select next_run_at from schedules where name = :name").param("name", name)
                .query(Timestamp.class).single().toInstant();
    }

    private static String uniqueName() {
        return "sched-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
