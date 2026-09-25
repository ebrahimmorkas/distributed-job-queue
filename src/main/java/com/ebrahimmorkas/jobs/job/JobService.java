package com.ebrahimmorkas.jobs.job;

import com.ebrahimmorkas.jobs.common.BadRequestException;
import com.ebrahimmorkas.jobs.common.ConflictException;
import com.ebrahimmorkas.jobs.common.NotFoundException;
import com.ebrahimmorkas.jobs.handler.JobHandlerRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class JobService {

    private final JobRepository jobRepository;
    private final JobHandlerRegistry handlerRegistry;
    private final Clock clock;

    @Value("${app.jobs.default-max-attempts:5}")
    private int defaultMaxAttempts;

    public Job submit(String type, String payload, Integer priority, Integer maxAttempts, Instant runAt,
                      String idempotencyKey) {
        if (handlerRegistry.find(type).isEmpty()) {
            throw new BadRequestException("Unknown job type '%s'. Registered types: %s"
                    .formatted(type, handlerRegistry.types()));
        }
        return jobRepository.insert(new NewJob(type, payload,
                priority == null ? 0 : priority,
                maxAttempts == null ? defaultMaxAttempts : maxAttempts,
                runAt == null ? clock.instant() : runAt,
                idempotencyKey));
    }

    public Job get(long id) {
        return jobRepository.findById(id).orElseThrow(() -> new NotFoundException("Job " + id + " not found"));
    }

    public List<Job> find(JobStatus status, String type, int page, int size) {
        return jobRepository.find(status, type, size, page * size);
    }

    public Job cancel(long id) {
        if (!jobRepository.cancel(id)) {
            throw new ConflictException("Job %d can only be cancelled while QUEUED (it is %s)".formatted(id, get(id).status()));
        }
        return get(id);
    }

    public Job retry(long id) {
        if (!jobRepository.requeueDead(id)) {
            throw new ConflictException("Only DEAD jobs can be retried (job %d is %s)".formatted(id, get(id).status()));
        }
        return get(id);
    }

    public Map<JobStatus, Long> stats() {
        return jobRepository.countByStatus();
    }
}
