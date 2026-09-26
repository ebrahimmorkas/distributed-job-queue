-- Recurring jobs. Cron expressions use Spring's 6-field format (second minute hour day month weekday), UTC.
CREATE TABLE schedules
(
    name             VARCHAR(100) PRIMARY KEY,
    cron             VARCHAR(100) NOT NULL,
    job_type         VARCHAR(100) NOT NULL,
    payload          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    priority         INTEGER      NOT NULL DEFAULT 0,
    enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    next_run_at      TIMESTAMPTZ  NOT NULL,
    last_enqueued_at TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_schedules_due ON schedules (next_run_at) WHERE enabled;
