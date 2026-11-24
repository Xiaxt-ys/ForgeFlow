CREATE TABLE IF NOT EXISTS ff_task (
    id              VARCHAR(64)  NOT NULL,
    tenant_id       VARCHAR(64)  NOT NULL,
    status          VARCHAR(32)  NOT NULL,
    version         BIGINT       NOT NULL,
    worker_id       VARCHAR(64)  NULL,
    idempotency_key VARCHAR(128) NULL,
    prompt          MEDIUMTEXT   NOT NULL,
    result          MEDIUMTEXT   NULL,
    error_code      VARCHAR(64)  NULL,
    error_message   TEXT         NULL,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ff_task_idem (tenant_id, idempotency_key),
    KEY idx_ff_task_status_updated (status, updated_at),
    KEY idx_ff_task_worker (worker_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ff_outbox (
    id            VARCHAR(36)  NOT NULL,
    task_id       VARCHAR(64)  NOT NULL,
    event_type    VARCHAR(32)  NOT NULL,
    payload       TEXT         NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    attempts      INT          NOT NULL,
    next_retry_at TIMESTAMP    NOT NULL,
    created_at    TIMESTAMP    NOT NULL,
    PRIMARY KEY (id),
    KEY idx_ff_outbox_status_retry (status, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ff_compensation (
    id           VARCHAR(36)  NOT NULL,
    task_id      VARCHAR(64)  NOT NULL,
    operation_id VARCHAR(36)  NOT NULL,
    seq          BIGINT       NOT NULL,
    tool         VARCHAR(128) NOT NULL,
    args_json    TEXT         NULL,
    description  VARCHAR(256) NULL,
    status       VARCHAR(16)  NOT NULL,
    attempts     INT          NOT NULL,
    created_at   TIMESTAMP    NOT NULL,
    updated_at   TIMESTAMP    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ff_comp_operation (operation_id),
    KEY idx_ff_comp_task_status (task_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ff_approval (
    id         VARCHAR(36)  NOT NULL,
    task_id    VARCHAR(64)  NOT NULL,
    turn_id    VARCHAR(64)  NOT NULL,
    call_id    VARCHAR(128) NOT NULL,
    tool       VARCHAR(128) NOT NULL,
    args_hash  VARCHAR(128) NOT NULL,
    args_json  TEXT         NULL,
    status     VARCHAR(16)  NOT NULL,
    created_at TIMESTAMP    NOT NULL,
    updated_at TIMESTAMP    NOT NULL,
    PRIMARY KEY (id),
    KEY idx_ff_approval_task (task_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ff_session (
    id         VARCHAR(64) NOT NULL,
    tenant_id  VARCHAR(64) NOT NULL,
    created_at TIMESTAMP   NOT NULL,
    updated_at TIMESTAMP   NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ff_session_summary (
    session_id       VARCHAR(64) NOT NULL,
    summary          TEXT        NOT NULL,
    structured_state TEXT        NULL,
    version          INT         NOT NULL DEFAULT 0,
    updated_at       TIMESTAMP   NOT NULL,
    PRIMARY KEY (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ff_session_fact (
    id             VARCHAR(36)  NOT NULL,
    session_id     VARCHAR(64)  NOT NULL,
    fact_key       VARCHAR(128) NOT NULL,
    fact_value     TEXT         NOT NULL,
    source_task_id VARCHAR(64)  NULL,
    importance     DOUBLE       NOT NULL DEFAULT 0.5,
    hits           INT          NOT NULL DEFAULT 0,
    last_used_at   TIMESTAMP    NULL,
    status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    version        INT          NOT NULL DEFAULT 1,
    embedding      TEXT         NULL,
    embedding_model VARCHAR(128) NULL,
    updated_at     TIMESTAMP    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ff_session_fact (session_id, fact_key),
    KEY idx_ff_session_fact_session (session_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ff_session_fact_history (
    id             VARCHAR(36)  NOT NULL,
    session_id     VARCHAR(64)  NOT NULL,
    fact_key       VARCHAR(128) NOT NULL,
    old_value      TEXT         NULL,
    new_value      TEXT         NULL,
    source_task_id VARCHAR(64)  NULL,
    kind           VARCHAR(16)  NOT NULL,
    created_at     TIMESTAMP    NOT NULL,
    PRIMARY KEY (id),
    KEY idx_ff_session_fact_history (session_id, fact_key, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ff_task_session (
    task_id    VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP   NOT NULL,
    PRIMARY KEY (task_id),
    KEY idx_ff_task_session_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
