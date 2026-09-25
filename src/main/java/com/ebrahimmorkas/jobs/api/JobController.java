package com.ebrahimmorkas.jobs.api;

import com.ebrahimmorkas.jobs.job.JobService;
import com.ebrahimmorkas.jobs.job.JobStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Jobs")
public class JobController {

    private final JobService jobService;

    @PostMapping("/jobs")
    @Operation(summary = "Enqueue a job", description = "Returns 202 Accepted: the job runs asynchronously on a worker")
    public ResponseEntity<JobResponse> submit(@Valid @RequestBody SubmitJobRequest request) {
        JobResponse job = JobResponse.from(jobService.submit(request.type(),
                request.payload() == null ? "{}" : request.payload().toString(),
                request.priority(), request.maxAttempts(), request.runAt(), request.idempotencyKey()));
        return ResponseEntity.accepted().location(URI.create("/api/jobs/" + job.id())).body(job);
    }

    @GetMapping("/jobs/{id}")
    @Operation(summary = "Get a job's current state")
    public JobResponse get(@PathVariable long id) {
        return JobResponse.from(jobService.get(id));
    }

    @GetMapping("/jobs")
    @Operation(summary = "List jobs, newest first, optionally filtered by status and type")
    public List<JobResponse> find(@RequestParam(required = false) JobStatus status,
                                  @RequestParam(required = false) String type,
                                  @RequestParam(defaultValue = "0") @Min(0) int page,
                                  @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return jobService.find(status, type, page, size).stream().map(JobResponse::from).toList();
    }

    @PostMapping("/jobs/{id}/cancel")
    @Operation(summary = "Cancel a job that hasn't started yet")
    public JobResponse cancel(@PathVariable long id) {
        return JobResponse.from(jobService.cancel(id));
    }

    @PostMapping("/jobs/{id}/retry")
    @Operation(summary = "Re-queue a DEAD job with a fresh attempt budget")
    public JobResponse retry(@PathVariable long id) {
        return JobResponse.from(jobService.retry(id));
    }

    @GetMapping("/queue/stats")
    @Operation(summary = "Number of jobs in each status")
    public Map<JobStatus, Long> stats() {
        return jobService.stats();
    }
}
