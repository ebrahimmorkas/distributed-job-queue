package com.ebrahimmorkas.jobs.api;

import com.ebrahimmorkas.jobs.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class JobApiIntegrationTest extends IntegrationTest {

    @Test
    void submittedJobIsQueuedWithDefaults() throws Exception {
        mockMvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\": \"email.send\", \"payload\": {\"to\": \"jane@example.com\"}}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", startsWith("/api/jobs/")))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.payload.to").value("jane@example.com"))
                .andExpect(jsonPath("$.maxAttempts").value(5))
                .andExpect(jsonPath("$.attempts").value(0));
    }

    @Test
    void sameIdempotencyKeyReturnsTheOriginalJob() throws Exception {
        String key = "welcome-email-" + UUID.randomUUID();
        JsonNode first = submit("{\"type\": \"email.send\", \"payload\": {\"to\": \"a@b.c\"}, \"idempotencyKey\": \"%s\"}".formatted(key));
        JsonNode second = submit("{\"type\": \"email.send\", \"payload\": {\"to\": \"a@b.c\"}, \"idempotencyKey\": \"%s\"}".formatted(key));

        assertThat(second.get("id").asLong()).isEqualTo(first.get("id").asLong());
    }

    @Test
    void unknownJobTypeIsRejected() throws Exception {
        mockMvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON).content("{\"type\": \"does.not.exist\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("email.send")));
    }

    @Test
    void queuedJobCanBeCancelledButOnlyOnce() throws Exception {
        String runAt = Instant.now().plus(1, ChronoUnit.HOURS).toString();
        long id = submit("{\"type\": \"email.send\", \"payload\": {\"to\": \"a@b.c\"}, \"runAt\": \"%s\"}".formatted(runAt))
                .get("id").asLong();

        mockMvc.perform(post("/api/jobs/{id}/cancel", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(post("/api/jobs/{id}/cancel", id)).andExpect(status().isConflict());
    }

    @Test
    void onlyDeadJobsCanBeRetried() throws Exception {
        long id = submit("{\"type\": \"email.send\", \"payload\": {\"to\": \"a@b.c\"}}").get("id").asLong();

        mockMvc.perform(post("/api/jobs/{id}/retry", id)).andExpect(status().isConflict());
    }

    @Test
    void jobsCanBeListedAndCountedByStatus() throws Exception {
        submit("{\"type\": \"demo.flaky\", \"payload\": {\"failTimes\": 0}}");

        mockMvc.perform(get("/api/jobs").param("type", "demo.flaky").param("status", "QUEUED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("demo.flaky"));
        mockMvc.perform(get("/api/queue/stats"))
                .andExpect(jsonPath("$.QUEUED").isNumber())
                .andExpect(jsonPath("$.DEAD").isNumber());
    }

    @Test
    void validationErrorsAreReported() throws Exception {
        mockMvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\": \"\", \"maxAttempts\": 0, \"priority\": 500}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.type").exists())
                .andExpect(jsonPath("$.errors.maxAttempts").exists())
                .andExpect(jsonPath("$.errors.priority").exists());
    }

    @Test
    void unknownJobReturns404() throws Exception {
        mockMvc.perform(get("/api/jobs/{id}", 999_999_999L)).andExpect(status().isNotFound());
    }
}
