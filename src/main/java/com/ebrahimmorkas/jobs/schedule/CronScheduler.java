package com.ebrahimmorkas.jobs.schedule;

import com.ebrahimmorkas.jobs.job.JobRepository;
import com.ebrahimmorkas.jobs.job.NewJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Turns due schedules into jobs, exactly once per cron slot across the whole cluster.
 *
 * <p>Two independent guards:
 * <ol>
 *   <li>a transaction-scoped <b>advisory lock</b> elects one instance per tick;</li>
 *   <li>each job's <b>idempotency key</b> is {@code schedule:<name>:<slot>}, so even if two
 *       instances somehow processed the same slot, the second insert is a no-op.</li>
 * </ol>
 *
 * <p>Misfire policy: after downtime a schedule fires <em>once</em> for all the slots it missed,
 * then resumes on its normal cadence, instead of flooding the queue with catch-up jobs.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CronScheduler {

    /** Arbitrary but fixed key identifying "the scheduler" among advisory locks. */
    static final long SCHEDULER_LOCK_KEY = 7_265_418_391L;
    private static final int BATCH = 100;

    private final ScheduleRepository scheduleRepository;
    private final JobRepository jobRepository;

    @Value("${app.jobs.default-max-attempts:5}")
    private int maxAttempts;

    @Scheduled(fixedDelayString = "${app.scheduler.poll-interval:5s}")
    public void scheduledTick() {
        try {
            tick();
        } catch (RuntimeException e) {
            log.warn("Scheduler tick failed: {}", e.getMessage());
        }
    }

    /** @return number of jobs enqueued by this instance */
    @Transactional
    public int tick() {
        if (!scheduleRepository.tryAcquireSchedulerLock(SCHEDULER_LOCK_KEY)) {
            return 0;
        }
        Instant now = scheduleRepository.databaseNow();
        int enqueued = 0;
        for (Schedule schedule : scheduleRepository.lockDue(BATCH)) {
            Instant slot = schedule.nextRunAt();
            jobRepository.insert(new NewJob(schedule.jobType(), schedule.payload(), schedule.priority(), maxAttempts,
                    null, "schedule:%s:%d".formatted(schedule.name(), slot.getEpochSecond())));
            scheduleRepository.advance(schedule.name(), nextAfter(schedule.cron(), now));
            enqueued++;
        }
        return enqueued;
    }

    static Instant nextAfter(String cron, Instant after) {
        ZonedDateTime next = CronExpression.parse(cron).next(after.atZone(ZoneOffset.UTC));
        if (next == null) {
            throw new IllegalArgumentException("Cron expression never fires: " + cron);
        }
        return next.toInstant();
    }
}
