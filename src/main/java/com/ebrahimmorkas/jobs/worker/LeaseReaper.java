package com.ebrahimmorkas.jobs.worker;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Recovers jobs from workers that died mid-job (crash, OOM kill, network partition).
 *
 * <p>Live workers keep extending their leases; a RUNNING job whose lease has expired therefore
 * belongs to a dead worker. It goes back to QUEUED (or DEAD if that was its last attempt). Every
 * instance runs the reaper: concurrent runs are harmless because the second UPDATE re-checks the
 * row after the first commits and no longer matches.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LeaseReaper {

    private final JdbcClient jdbc;

    @Scheduled(fixedDelayString = "${app.worker.reaper-interval:30s}")
    public void scheduledReap() {
        try {
            reap();
        } catch (RuntimeException e) {
            log.warn("Lease reaper failed: {}", e.getMessage());
        }
    }

    /** @return number of jobs recovered */
    public int reap() {
        int recovered = jdbc.sql("""
                        update jobs
                           set status       = case when attempts >= max_attempts then 'DEAD' else 'QUEUED' end,
                               completed_at = case when attempts >= max_attempts then now() end,
                               last_error   = 'Lease expired: worker ' || locked_by || ' stopped responding',
                               locked_by    = null,
                               locked_until = null,
                               run_at       = now(),
                               updated_at   = now()
                         where status = 'RUNNING' and locked_until < now()""")
                .update();
        if (recovered > 0) {
            log.warn("Recovered {} job(s) from expired leases", recovered);
        }
        return recovered;
    }
}
