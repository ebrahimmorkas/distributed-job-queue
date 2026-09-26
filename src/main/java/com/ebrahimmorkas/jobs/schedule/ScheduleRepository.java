package com.ebrahimmorkas.jobs.schedule;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ScheduleRepository {

    private static final RowMapper<Schedule> ROW_MAPPER = (rs, row) -> new Schedule(
            rs.getString("name"),
            rs.getString("cron"),
            rs.getString("job_type"),
            rs.getString("payload"),
            rs.getInt("priority"),
            rs.getBoolean("enabled"),
            rs.getTimestamp("next_run_at").toInstant(),
            rs.getTimestamp("last_enqueued_at") == null ? null : rs.getTimestamp("last_enqueued_at").toInstant(),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcClient jdbc;

    /** @return false if a schedule with this name already exists */
    public boolean insert(String name, String cron, String jobType, String payload, int priority, Instant nextRunAt) {
        return jdbc.sql("""
                        insert into schedules (name, cron, job_type, payload, priority, next_run_at)
                        values (:name, :cron, :jobType, :payload::jsonb, :priority, :nextRunAt)
                        on conflict (name) do nothing""")
                .param("name", name).param("cron", cron).param("jobType", jobType).param("payload", payload)
                .param("priority", priority).param("nextRunAt", Timestamp.from(nextRunAt))
                .update() == 1;
    }

    public Optional<Schedule> find(String name) {
        return jdbc.sql("select * from schedules where name = :name").param("name", name).query(ROW_MAPPER).optional();
    }

    public List<Schedule> findAll() {
        return jdbc.sql("select * from schedules order by name").query(ROW_MAPPER).list();
    }

    /** Due schedules, locked so a concurrent tick (should the advisory lock ever be bypassed) skips them. */
    public List<Schedule> lockDue(int limit) {
        return jdbc.sql("""
                        select * from schedules
                        where enabled and next_run_at <= now()
                        order by next_run_at
                        limit :limit
                        for update skip locked""")
                .param("limit", limit)
                .query(ROW_MAPPER)
                .list();
    }

    public void advance(String name, Instant nextRunAt) {
        jdbc.sql("update schedules set next_run_at = :next, last_enqueued_at = now() where name = :name")
                .param("name", name).param("next", Timestamp.from(nextRunAt))
                .update();
    }

    public boolean setEnabled(String name, boolean enabled, Instant nextRunAt) {
        return jdbc.sql("update schedules set enabled = :enabled, next_run_at = :next where name = :name")
                .param("name", name).param("enabled", enabled).param("next", Timestamp.from(nextRunAt))
                .update() == 1;
    }

    /** The database clock is the single source of time for next_run_at comparisons. */
    public Instant databaseNow() {
        return jdbc.sql("select now()").query(Timestamp.class).single().toInstant();
    }

    public boolean delete(String name) {
        return jdbc.sql("delete from schedules where name = :name").param("name", name).update() == 1;
    }

    /**
     * Transaction-scoped PostgreSQL advisory lock: only one instance in the cluster gets it per tick,
     * and it is released automatically at commit/rollback, even if the holder crashes.
     */
    public boolean tryAcquireSchedulerLock(long key) {
        return Boolean.TRUE.equals(jdbc.sql("select pg_try_advisory_xact_lock(:key)")
                .param("key", key).query(Boolean.class).single());
    }
}
