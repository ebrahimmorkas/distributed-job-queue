package com.ebrahimmorkas.jobs.schedule;

import com.ebrahimmorkas.jobs.common.BadRequestException;
import com.ebrahimmorkas.jobs.common.ConflictException;
import com.ebrahimmorkas.jobs.common.NotFoundException;
import com.ebrahimmorkas.jobs.handler.JobHandlerRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ScheduleService {

    private final ScheduleRepository scheduleRepository;
    private final JobHandlerRegistry handlerRegistry;

    public Schedule create(String name, String cron, String jobType, String payload, int priority) {
        if (!CronExpression.isValidExpression(cron)) {
            throw new BadRequestException("Invalid cron expression '%s' (expected 6 fields: sec min hour day month weekday)"
                    .formatted(cron));
        }
        if (handlerRegistry.find(jobType).isEmpty()) {
            throw new BadRequestException("Unknown job type '%s'".formatted(jobType));
        }
        if (!scheduleRepository.insert(name, cron, jobType, payload, priority,
                CronScheduler.nextAfter(cron, scheduleRepository.databaseNow()))) {
            throw new ConflictException("A schedule named '%s' already exists".formatted(name));
        }
        return get(name);
    }

    public List<Schedule> findAll() {
        return scheduleRepository.findAll();
    }

    public Schedule get(String name) {
        return scheduleRepository.find(name).orElseThrow(() -> new NotFoundException("Schedule " + name + " not found"));
    }

    /** Pausing and resuming recomputes the next run from now, so a resumed schedule never back-fills. */
    public Schedule setEnabled(String name, boolean enabled) {
        Schedule schedule = get(name);
        scheduleRepository.setEnabled(name, enabled, CronScheduler.nextAfter(schedule.cron(), scheduleRepository.databaseNow()));
        return get(name);
    }

    public void delete(String name) {
        if (!scheduleRepository.delete(name)) {
            throw new NotFoundException("Schedule " + name + " not found");
        }
    }
}
