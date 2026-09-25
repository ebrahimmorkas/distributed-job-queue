package com.ebrahimmorkas.jobs.worker;

import com.ebrahimmorkas.jobs.handler.JobHandler;
import com.ebrahimmorkas.jobs.handler.JobHandler.JobContext;
import com.ebrahimmorkas.jobs.handler.JobHandlerRegistry;
import com.ebrahimmorkas.jobs.job.Job;
import com.ebrahimmorkas.jobs.job.JobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Polls the queue and executes jobs, one virtual thread per job.
 *
 * <p>Virtual threads suit jobs that mostly wait on I/O (HTTP calls, email, DB): thousands can be
 * in flight cheaply. A {@link Semaphore} still bounds concurrency so a worker never claims more
 * than it can run, which leaves the rest of the queue for other instances.
 */
@Slf4j
public class JobWorker implements SmartLifecycle {

    @Getter
    private final String workerId;
    private final JobRepository jobRepository;
    private final JobHandlerRegistry handlerRegistry;
    private final RetryPolicy retryPolicy;
    private final ObjectMapper objectMapper;
    private final WorkerProperties properties;
    private final Clock clock;
    private final Semaphore slots;

    private volatile boolean running;
    private ExecutorService executor;
    private Thread poller;

    public JobWorker(String workerId, JobRepository jobRepository, JobHandlerRegistry handlerRegistry,
                     RetryPolicy retryPolicy, ObjectMapper objectMapper, WorkerProperties properties, Clock clock) {
        this.workerId = workerId;
        this.jobRepository = jobRepository;
        this.handlerRegistry = handlerRegistry;
        this.retryPolicy = retryPolicy;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
        this.slots = new Semaphore(properties.concurrency());
    }

    @Override
    public void start() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        running = true;
        poller = Thread.ofVirtual().name("job-poller-" + workerId).start(this::pollLoop);
        log.info("Worker {} started (concurrency {})", workerId, properties.concurrency());
    }

    private void pollLoop() {
        while (running) {
            try {
                if (pollOnce() == 0) {
                    Thread.sleep(properties.pollInterval());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.warn("Polling failed, backing off: {}", e.getMessage());
                sleepQuietly();
            }
        }
    }

    /** Claims as many jobs as there are free slots and starts them. @return jobs claimed */
    int pollOnce() throws InterruptedException {
        int free = slots.availablePermits();
        if (free == 0) {
            Thread.sleep(properties.pollInterval());
            return 0;
        }
        List<Job> claimed = jobRepository.claim(workerId, free, properties.lease());
        for (Job job : claimed) {
            slots.acquire();
            executor.submit(() -> {
                try {
                    execute(job);
                } finally {
                    slots.release();
                }
            });
        }
        return claimed.size();
    }

    void execute(Job job) {
        JobHandler handler = handlerRegistry.find(job.type()).orElse(null);
        if (handler == null) {
            jobRepository.markDead(job.id(), workerId, "No handler registered for type " + job.type());
            return;
        }
        try {
            handler.handle(new JobContext(job.id(), job.attempts(), objectMapper.readTree(job.payload())));
            if (!jobRepository.markSucceeded(job.id(), workerId)) {
                log.warn("Job {} finished but its lease had been lost; another worker owns it now", job.id());
            }
        } catch (Exception e) {
            onFailure(job, e);
        }
    }

    private void onFailure(Job job, Exception e) {
        String error = e.getClass().getSimpleName() + ": " + e.getMessage();
        if (job.attempts() >= job.maxAttempts()) {
            jobRepository.markDead(job.id(), workerId, error);
            log.warn("Job {} ({}) is DEAD after {} attempts: {}", job.id(), job.type(), job.attempts(), error);
        } else {
            jobRepository.scheduleRetry(job.id(), workerId, error,
                    clock.instant().plus(retryPolicy.delayBeforeRetry(job.attempts())));
            log.info("Job {} ({}) failed attempt {}/{}, retry scheduled: {}",
                    job.id(), job.type(), job.attempts(), job.maxAttempts(), error);
        }
    }

    /**
     * Graceful shutdown: stop claiming, then give in-flight jobs time to finish. Jobs still running
     * after the timeout keep their lease; once it expires they are re-queued for another worker.
     */
    @Override
    public void stop() {
        running = false;
        if (poller != null) {
            // Don't interrupt: interrupting a thread blocked in JDBC I/O closes the pooled connection's
            // socket. The loop sees running == false within one poll interval and exits on its own.
            try {
                if (!poller.join(properties.pollInterval().plus(properties.shutdownTimeout()))) {
                    poller.interrupt();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(properties.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                    log.warn("Worker {} stopped with jobs still running; their leases will expire", workerId);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("Worker {} stopped", workerId);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Start after everything else and stop before the datasource is closed. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(properties.pollInterval());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
