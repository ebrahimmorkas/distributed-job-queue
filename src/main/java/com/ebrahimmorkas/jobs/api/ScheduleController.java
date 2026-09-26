package com.ebrahimmorkas.jobs.api;

import com.ebrahimmorkas.jobs.schedule.Schedule;
import com.ebrahimmorkas.jobs.schedule.ScheduleService;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/schedules")
@RequiredArgsConstructor
@Tag(name = "Schedules")
public class ScheduleController {

    private final ScheduleService scheduleService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a recurring job (cron in UTC, 6 fields incl. seconds)")
    public Schedule create(@Valid @RequestBody CreateScheduleRequest request) {
        return scheduleService.create(request.name(), request.cron(), request.type(),
                request.payload() == null ? "{}" : request.payload().toString(),
                request.priority() == null ? 0 : request.priority());
    }

    @GetMapping
    @Operation(summary = "List schedules")
    public List<Schedule> findAll() {
        return scheduleService.findAll();
    }

    @PostMapping("/{name}/pause")
    @Operation(summary = "Pause a schedule")
    public Schedule pause(@PathVariable String name) {
        return scheduleService.setEnabled(name, false);
    }

    @PostMapping("/{name}/resume")
    @Operation(summary = "Resume a paused schedule from its next future slot")
    public Schedule resume(@PathVariable String name) {
        return scheduleService.setEnabled(name, true);
    }

    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a schedule")
    public void delete(@PathVariable String name) {
        scheduleService.delete(name);
    }

    public record CreateScheduleRequest(
            @NotBlank @Size(max = 100) @Pattern(regexp = "^[a-z0-9][a-z0-9._-]*$") @Schema(example = "nightly-report") String name,
            @NotBlank @Schema(example = "0 0 2 * * *") String cron,
            @NotBlank @Schema(example = "email.send") String type,
            JsonNode payload,
            @Min(-100) @Max(100) Integer priority) {
    }
}
