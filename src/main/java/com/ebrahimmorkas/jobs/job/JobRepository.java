package com.ebrahimmorkas.jobs.job;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Plain SQL through {@link JdbcClient}: in a queue, the exact statements are the design. */
@Repository
@RequiredArgsConstructor
public class JobRepository {

    static final RowMapper<Job> JOB_ROW_MAPPER = JobRepository::mapJob;

    private final JdbcClient jdbc;

    /**
     * Inserts the job, or returns the existing one when the idempotency key was already used.
     * {@code ON CONFLICT DO NOTHING} makes concurrent duplicate submissions safe.
     */
    public Job insert(NewJob job) {
        Optional<Job> inserted = jdbc.sql("""
                        insert into jobs (type, payload, status, priority, max_attempts, run_at, idempotency_key)
                        values (:type, :payload::jsonb, 'QUEUED', :priority, :maxAttempts, :runAt, :idempotencyKey)
                        on conflict (idempotency_key) do nothing
                        returning *""")
                .param("type", job.type())
                .param("payload", job.payload())
                .param("priority", job.priority())
                .param("maxAttempts", job.maxAttempts())
                .param("runAt", Timestamp.from(job.runAt()))
                .param("idempotencyKey", job.idempotencyKey())
                .query(JOB_ROW_MAPPER)
                .optional();
        return inserted.orElseGet(() -> findByIdempotencyKey(job.idempotencyKey()).orElseThrow());
    }

    public Optional<Job> findById(long id) {
        return jdbc.sql("select * from jobs where id = :id").param("id", id).query(JOB_ROW_MAPPER).optional();
    }

    public Optional<Job> findByIdempotencyKey(String key) {
        return jdbc.sql("select * from jobs where idempotency_key = :key").param("key", key)
                .query(JOB_ROW_MAPPER).optional();
    }

    public List<Job> find(JobStatus status, String type, int limit, int offset) {
        return jdbc.sql("""
                        select * from jobs
                        where (cast(:status as varchar) is null or status = :status)
                          and (cast(:type as varchar) is null or type = :type)
                        order by id desc
                        limit :limit offset :offset""")
                .param("status", status == null ? null : status.name())
                .param("type", type)
                .param("limit", limit)
                .param("offset", offset)
                .query(JOB_ROW_MAPPER)
                .list();
    }

    /** @return true if the job was still QUEUED and is now CANCELLED */
    public boolean cancel(long id) {
        return jdbc.sql("""
                        update jobs set status = 'CANCELLED', updated_at = now(), completed_at = now()
                        where id = :id and status = 'QUEUED'""")
                .param("id", id)
                .update() == 1;
    }

    /** Puts a DEAD job back in the queue with a fresh attempt budget. */
    public boolean requeueDead(long id) {
        return jdbc.sql("""
                        update jobs set status = 'QUEUED', attempts = 0, run_at = now(), last_error = null,
                                        updated_at = now(), completed_at = null
                        where id = :id and status = 'DEAD'""")
                .param("id", id)
                .update() == 1;
    }

    /**
     * Atomically claims up to {@code limit} due jobs for this worker.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} is what makes many competing workers safe and fast: rows
     * locked by another worker's in-flight claim are skipped instead of waited on, so workers never
     * block each other and a row can never be claimed twice. The claim also starts a lease and
     * counts the attempt, all in one statement.
     */
    public List<Job> claim(String workerId, int limit, Duration lease) {
        return jdbc.sql("""
                        update jobs
                           set status = 'RUNNING', locked_by = :workerId,
                               locked_until = now() + make_interval(secs => :leaseSeconds),
                               attempts = attempts + 1, updated_at = now()
                         where id in (select id from jobs
                                       where status = 'QUEUED' and run_at <= now()
                                       order by priority desc, run_at, id
                                       limit :limit
                                       for update skip locked)
                        returning *""")
                .param("workerId", workerId)
                .param("leaseSeconds", lease.toSeconds())
                .param("limit", limit)
                .query(JOB_ROW_MAPPER)
                .list();
    }

    /**
     * Completion is fenced on {@code locked_by}: if this worker's lease expired and the job was
     * handed to someone else, the stale worker's update matches no row and changes nothing.
     *
     * @return false if this worker no longer owns the job
     */
    public boolean markSucceeded(long id, String workerId) {
        return jdbc.sql("""
                        update jobs set status = 'SUCCEEDED', locked_by = null, locked_until = null,
                                        last_error = null, completed_at = now(), updated_at = now()
                        where id = :id and locked_by = :workerId and status = 'RUNNING'""")
                .param("id", id).param("workerId", workerId)
                .update() == 1;
    }

    public boolean scheduleRetry(long id, String workerId, String error, Instant runAt) {
        return jdbc.sql("""
                        update jobs set status = 'QUEUED', locked_by = null, locked_until = null,
                                        last_error = :error, run_at = :runAt, updated_at = now()
                        where id = :id and locked_by = :workerId and status = 'RUNNING'""")
                .param("id", id).param("workerId", workerId).param("error", error)
                .param("runAt", Timestamp.from(runAt))
                .update() == 1;
    }

    public boolean markDead(long id, String workerId, String error) {
        return jdbc.sql("""
                        update jobs set status = 'DEAD', locked_by = null, locked_until = null,
                                        last_error = :error, completed_at = now(), updated_at = now()
                        where id = :id and locked_by = :workerId and status = 'RUNNING'""")
                .param("id", id).param("workerId", workerId).param("error", error)
                .update() == 1;
    }

    public Map<JobStatus, Long> countByStatus() {
        Map<JobStatus, Long> counts = new EnumMap<>(JobStatus.class);
        for (JobStatus status : JobStatus.values()) {
            counts.put(status, 0L);
        }
        jdbc.sql("select status, count(*) as n from jobs group by status")
                .query((rs, row) -> Map.entry(JobStatus.valueOf(rs.getString("status")), rs.getLong("n")))
                .list()
                .forEach(e -> counts.put(e.getKey(), e.getValue()));
        return counts;
    }

    private static Job mapJob(ResultSet rs, int row) throws SQLException {
        return new Job(
                rs.getLong("id"),
                rs.getString("type"),
                rs.getString("payload"),
                JobStatus.valueOf(rs.getString("status")),
                rs.getInt("priority"),
                rs.getInt("attempts"),
                rs.getInt("max_attempts"),
                instant(rs, "run_at"),
                rs.getString("locked_by"),
                instant(rs, "locked_until"),
                rs.getString("last_error"),
                rs.getString("idempotency_key"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                instant(rs, "completed_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
