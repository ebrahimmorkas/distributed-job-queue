package com.ebrahimmorkas.jobs.metrics;

import com.ebrahimmorkas.jobs.job.JobStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Queue-level gauges. They're refreshed on a timer rather than queried on every Prometheus scrape,
 * so a scrape never puts load on the database.
 *
 * <ul>
 *   <li>{@code jobs.queue.depth{status}}: jobs per status</li>
 *   <li>{@code jobs.queue.lag.seconds}: age of the oldest job that is due but not yet running,
 *       the best single signal that workers are falling behind (alert on this, not on depth)</li>
 * </ul>
 */
@Slf4j
@Component
public class QueueMetrics {

    private final JdbcClient jdbc;
    private final Map<JobStatus, AtomicLong> depth = new EnumMap<>(JobStatus.class);
    private final AtomicLong lagSeconds = new AtomicLong();

    public QueueMetrics(JdbcClient jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        for (JobStatus status : JobStatus.values()) {
            AtomicLong value = new AtomicLong();
            depth.put(status, value);
            Gauge.builder("jobs.queue.depth", value, AtomicLong::get)
                    .tag("status", status.name())
                    .description("Number of jobs in each status")
                    .register(registry);
        }
        Gauge.builder("jobs.queue.lag.seconds", lagSeconds, AtomicLong::get)
                .description("Age of the oldest due job still waiting to run")
                .baseUnit("seconds")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${app.metrics.refresh-interval:10s}")
    public void refresh() {
        try {
            depth.values().forEach(v -> v.set(0));
            jdbc.sql("select status, count(*) as n from jobs group by status")
                    .query((rs, row) -> Map.entry(JobStatus.valueOf(rs.getString("status")), rs.getLong("n")))
                    .list()
                    .forEach(e -> depth.get(e.getKey()).set(e.getValue()));
            lagSeconds.set(jdbc.sql("""
                            select coalesce(extract(epoch from now() - min(run_at)), 0)::bigint
                            from jobs where status = 'QUEUED' and run_at <= now()""")
                    .query(Long.class).single());
        } catch (RuntimeException e) {
            log.warn("Could not refresh queue metrics: {}", e.getMessage());
        }
    }
}
