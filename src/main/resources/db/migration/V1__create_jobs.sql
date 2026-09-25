CREATE TABLE jobs
(
    id              BIGSERIAL PRIMARY KEY,
    type            VARCHAR(100) NOT NULL,
    payload         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    status          VARCHAR(20)  NOT NULL,
    priority        INTEGER      NOT NULL DEFAULT 0,
    attempts        INTEGER      NOT NULL DEFAULT 0,
    max_attempts    INTEGER      NOT NULL CHECK (max_attempts > 0),
    run_at          TIMESTAMPTZ  NOT NULL,
    locked_by       VARCHAR(100),
    locked_until    TIMESTAMPTZ,
    last_error      TEXT,
    idempotency_key VARCHAR(200) UNIQUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    CONSTRAINT chk_jobs_status CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'DEAD', 'CANCELLED'))
);

-- The workers' hot query: "highest-priority due jobs". A partial index keeps it small because
-- only QUEUED rows are indexed, however many millions of finished jobs accumulate.
CREATE INDEX idx_jobs_ready ON jobs (priority DESC, run_at, id) WHERE status = 'QUEUED';

-- Used by the lease reaper to find jobs held by crashed workers.
CREATE INDEX idx_jobs_running_lease ON jobs (locked_until) WHERE status = 'RUNNING';

CREATE INDEX idx_jobs_status_type ON jobs (status, type);
