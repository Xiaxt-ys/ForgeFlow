CREATE TABLE IF NOT EXISTS ff_task (
    id              VARCHAR(64)  PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    status          VARCHAR(32)  NOT NULL,
    version         BIGINT       NOT NULL,
    worker_id       VARCHAR(64),
    idempotency_key VARCHAR(128),
    prompt          TEXT         NOT NULL,
    result          TEXT,
    error_code      VARCHAR(64),
    error_message   TEXT,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    CONSTRAINT uk_ff_task_idem UNIQUE (tenant_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_ff_task_status_updated ON ff_task (status, updated_at);
CREATE INDEX IF NOT EXISTS idx_ff_task_worker ON ff_task (worker_id);

CREATE TABLE IF NOT EXISTS ff_outbox (
    id          VARCHAR(36) PRIMARY KEY,
    task_id     VARCHAR(64) NOT NULL,
    event_type  VARCHAR(32) NOT NULL,
    payload     TEXT        NOT NULL,
    status      VARCHAR(16) NOT NULL,
    attempts    INT         NOT NULL,
    next_retry_at TIMESTAMP NOT NULL,
    created_at  TIMESTAMP   NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ff_outbox_status_retry ON ff_outbox (status, next_retry_at);

CREATE TABLE IF NOT EXISTS ff_compensation (
    id           VARCHAR(36)  PRIMARY KEY,
    task_id      VARCHAR(64)  NOT NULL,
    operation_id VARCHAR(36)  NOT NULL,
    seq          BIGINT       NOT NULL,
    tool         VARCHAR(128) NOT NULL,
    args_json    TEXT,
    description  VARCHAR(256),
    status       VARCHAR(16)  NOT NULL,
    attempts     INT          NOT NULL,
    created_at   TIMESTAMP    NOT NULL,
    updated_at   TIMESTAMP    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ff_comp_task_status ON ff_compensation (task_id, status);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ff_comp_operation ON ff_compensation (operation_id);

CREATE TABLE IF NOT EXISTS ff_approval (
    id         VARCHAR(36)  PRIMARY KEY,
    task_id    VARCHAR(64)  NOT NULL,
    turn_id    VARCHAR(64)  NOT NULL,
    call_id    VARCHAR(128) NOT NULL,
    tool       VARCHAR(128) NOT NULL,
    args_hash  VARCHAR(128) NOT NULL,
    args_json  TEXT,
    status     VARCHAR(16)  NOT NULL,
    created_at TIMESTAMP    NOT NULL,
    updated_at TIMESTAMP    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ff_approval_task ON ff_approval (task_id, status);

CREATE TABLE IF NOT EXISTS ff_session (
    id         VARCHAR(64) PRIMARY KEY,
    tenant_id  VARCHAR(64) NOT NULL,
    created_at TIMESTAMP   NOT NULL,
    updated_at TIMESTAMP   NOT NULL
);

CREATE TABLE IF NOT EXISTS ff_session_summary (
    session_id       VARCHAR(64) PRIMARY KEY,
    summary          TEXT        NOT NULL,
    structured_state TEXT,
    version          INT         NOT NULL DEFAULT 0,
    updated_at       TIMESTAMP   NOT NULL
);

CREATE TABLE IF NOT EXISTS ff_session_fact (
    id             VARCHAR(36)  PRIMARY KEY,
    session_id     VARCHAR(64)  NOT NULL,
    fact_key       VARCHAR(128) NOT NULL,
    fact_value     TEXT         NOT NULL,
    source_task_id VARCHAR(64),
    importance     DOUBLE       NOT NULL DEFAULT 0.5,
    hits           INT          NOT NULL DEFAULT 0,
    last_used_at   TIMESTAMP,
    status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    version        INT          NOT NULL DEFAULT 1,
    embedding      TEXT,
    embedding_model VARCHAR(128),
    updated_at     TIMESTAMP    NOT NULL,
    CONSTRAINT uk_ff_session_fact UNIQUE (session_id, fact_key)
);

CREATE INDEX IF NOT EXISTS idx_ff_session_fact_session ON ff_session_fact (session_id, status);

CREATE TABLE IF NOT EXISTS ff_session_fact_history (
    id             VARCHAR(36)  PRIMARY KEY,
    session_id     VARCHAR(64)  NOT NULL,
    fact_key       VARCHAR(128) NOT NULL,
    old_value      TEXT,
    new_value      TEXT,
    source_task_id VARCHAR(64),
    kind           VARCHAR(16)  NOT NULL,
    created_at     TIMESTAMP    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ff_session_fact_history
    ON ff_session_fact_history (session_id, fact_key, created_at);

CREATE TABLE IF NOT EXISTS ff_task_session (
    task_id    VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP   NOT NULL
);
