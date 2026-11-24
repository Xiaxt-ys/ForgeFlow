-- Upgrade an existing ForgeFlow MySQL database created before memory lifecycle support.
-- Fresh databases can use schema-mysql.sql only; this file is for already-populated ones.

ALTER TABLE ff_session_summary
    ADD COLUMN version INT NOT NULL DEFAULT 0;

-- Long-context tasks: TEXT caps at 64 KB; a near-window prompt does not fit.
ALTER TABLE ff_task
    MODIFY COLUMN prompt MEDIUMTEXT NOT NULL,
    MODIFY COLUMN result MEDIUMTEXT NULL;

ALTER TABLE ff_session_fact
    ADD COLUMN importance DOUBLE NOT NULL DEFAULT 0.5,
    ADD COLUMN hits INT NOT NULL DEFAULT 0,
    ADD COLUMN last_used_at TIMESTAMP NULL,
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN version INT NOT NULL DEFAULT 1,
    ADD COLUMN embedding TEXT NULL,
    ADD COLUMN embedding_model VARCHAR(128) NULL;

ALTER TABLE ff_session_fact
    DROP INDEX idx_ff_session_fact_session,
    ADD INDEX idx_ff_session_fact_session (session_id, status);

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
