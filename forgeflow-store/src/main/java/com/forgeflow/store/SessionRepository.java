package com.forgeflow.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Session registry, rolling summary, facts, fact history and the task→session binding.
 *
 * <p>A session belongs to one tenant; crossing tenants is rejected rather than silently
 * mixing memory.</p>
 *
 * <p>Facts are not append-only: they carry a lifecycle ({@code ACTIVE}/{@code ARCHIVED}), a
 * reinforcement counter, an importance weight and a version. Every value change is recorded in
 * {@code ff_session_fact_history} before the current value is overwritten, so a conflict can be
 * reviewed and rolled back instead of vanishing.</p>
 */
@Repository
public class SessionRepository {

    private static final Logger log = LoggerFactory.getLogger(SessionRepository.class);

    private static final RowMapper<SessionFact> FACT_MAPPER = (rs, rowNum) -> new SessionFact(
            rs.getString("fact_key"),
            rs.getString("fact_value"),
            rs.getString("source_task_id"),
            rs.getTimestamp("updated_at").toInstant(),
            rs.getDouble("importance"),
            rs.getInt("hits"),
            rs.getTimestamp("last_used_at") == null ? null
                    : rs.getTimestamp("last_used_at").toInstant(),
            rs.getString("status"),
            rs.getInt("version"));

    private static final RowMapper<FactHistory> HISTORY_MAPPER = (rs, rowNum) -> new FactHistory(
            rs.getString("id"),
            rs.getString("fact_key"),
            rs.getString("old_value"),
            rs.getString("new_value"),
            rs.getString("source_task_id"),
            rs.getString("kind"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public SessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates the session if absent; fails when it exists under another tenant. */
    public void ensureSession(String sessionId, String tenantId) {
        Optional<String> existing = tenantOf(sessionId);
        if (existing.isPresent()) {
            if (!existing.get().equals(tenantId)) {
                throw new IllegalArgumentException("session " + sessionId + " belongs to another tenant");
            }
            return;
        }
        Timestamp now = Timestamp.from(Instant.now());
        try {
            jdbc.update("INSERT INTO ff_session (id, tenant_id, created_at, updated_at) VALUES (?, ?, ?, ?)",
                    sessionId, tenantId, now, now);
        } catch (DuplicateKeyException race) {
            // Another request created it first; only a tenant mismatch is an error.
            if (!tenantOf(sessionId).filter(tenantId::equals).isPresent()) {
                throw new IllegalArgumentException("session " + sessionId + " belongs to another tenant");
            }
        }
    }

    public Optional<String> tenantOf(String sessionId) {
        List<String> rows = jdbc.query("SELECT tenant_id FROM ff_session WHERE id = ?",
                (rs, rowNum) -> rs.getString(1), sessionId);
        return rows.stream().findFirst();
    }

    public void bindTask(String taskId, String sessionId) {
        try {
            jdbc.update("INSERT INTO ff_task_session (task_id, session_id, created_at) VALUES (?, ?, ?)",
                    taskId, sessionId, Timestamp.from(Instant.now()));
        } catch (DuplicateKeyException ignored) {
            // Idempotent re-submission of the same task id.
        }
    }

    public Optional<String> sessionForTask(String taskId) {
        List<String> rows = jdbc.query("SELECT session_id FROM ff_task_session WHERE task_id = ?",
                (rs, rowNum) -> rs.getString(1), taskId);
        return rows.stream().findFirst();
    }

    public Optional<SessionSummary> summary(String sessionId) {
        List<SessionSummary> rows = jdbc.query(
                "SELECT summary, structured_state, version, updated_at FROM ff_session_summary "
                        + "WHERE session_id = ?",
                (rs, rowNum) -> new SessionSummary(sessionId, rs.getString("summary"),
                        rs.getString("structured_state"),
                        rs.getInt("version"),
                        rs.getTimestamp("updated_at").toInstant()),
                sessionId);
        return rows.stream().findFirst();
    }

    /**
     * Compare-and-set on the summary version, with a bounded retry. Concurrent writers of the
     * same session therefore do not silently overwrite each other; after the retries the last
     * writer still wins (memory must never block a task), but that case is logged.
     */
    public void saveSummary(String sessionId, String summary, String structuredState) {
        for (int attempt = 0; attempt < 3; attempt++) {
            Optional<SessionSummary> current = summary(sessionId);
            Timestamp now = Timestamp.from(Instant.now());
            if (current.isEmpty()) {
                try {
                    jdbc.update("""
                                    INSERT INTO ff_session_summary
                                        (session_id, summary, structured_state, version, updated_at)
                                    VALUES (?, ?, ?, 0, ?)
                                    """,
                            sessionId, summary, structuredState, now);
                    return;
                } catch (DuplicateKeyException race) {
                    continue;
                }
            }
            int updated = jdbc.update("""
                            UPDATE ff_session_summary
                               SET summary = ?, structured_state = ?, version = version + 1, updated_at = ?
                             WHERE session_id = ? AND version = ?
                            """,
                    summary, structuredState, now, sessionId, current.get().version());
            if (updated > 0) {
                return;
            }
        }
        log.warn("session.summary.cas_exhausted session={} — last writer wins", sessionId);
        jdbc.update("""
                        UPDATE ff_session_summary
                           SET summary = ?, structured_state = ?, version = version + 1, updated_at = ?
                         WHERE session_id = ?
                        """,
                summary, structuredState, Timestamp.from(Instant.now()), sessionId);
    }

    public Optional<SessionFact> fact(String sessionId, String key) {
        List<SessionFact> rows = jdbc.query("SELECT * FROM ff_session_fact "
                        + "WHERE session_id = ? AND fact_key = ?",
                FACT_MAPPER, sessionId, key);
        return rows.stream().findFirst();
    }

    /**
     * Writes one fact, merging with the existing value when the key is already known.
     *
     * <ul>
     *   <li>same value: touch the timestamp, nothing else;</li>
     *   <li>one value contains the other: {@code REFINED}, the new value wins;</li>
     *   <li>incompatible values: {@code CONFLICT} recorded in history; the new value wins, or
     *       both values are kept when {@code keepBoth} is on.</li>
     * </ul>
     */
    public Optional<FactWrite> upsertFact(String sessionId, String key, String value,
                                          String sourceTaskId, boolean keepBoth,
                                          EmbeddingProvider embedder) {
        for (int attempt = 0; attempt < 2; attempt++) {
            Optional<SessionFact> existing = fact(sessionId, key);
            Timestamp now = Timestamp.from(Instant.now());
            if (existing.isEmpty()) {
                try {
                    Embedding embedding = embedder.embedOne(key + " " + value);
                    String id = UUID.randomUUID().toString();
                    jdbc.update("""
                                    INSERT INTO ff_session_fact
                                        (id, session_id, fact_key, fact_value, source_task_id,
                                         importance, hits, status, version, embedding,
                                         embedding_model, updated_at)
                                    VALUES (?, ?, ?, ?, ?, 0.5, 0, 'ACTIVE', 1, ?, ?, ?)
                                    """,
                            id, sessionId, key, value, sourceTaskId,
                            TextVectorizer.encode(embedding.first()), embedding.model(), now);
                    return Optional.of(new FactWrite(id, embedding.model(), embedding.first()));
                } catch (DuplicateKeyException race) {
                    continue;
                }
            }
            SessionFact old = existing.get();
            String existingId = jdbc.queryForObject(
                    "SELECT id FROM ff_session_fact WHERE session_id = ? AND fact_key = ?",
                    String.class, sessionId, key);
            if (value.equals(old.factValue())) {
                jdbc.update("UPDATE ff_session_fact SET updated_at = ? "
                        + "WHERE session_id = ? AND fact_key = ?", now, sessionId, key);
                return Optional.of(new FactWrite(existingId, null, null));
            }
            String kind = old.factValue().contains(value) || value.contains(old.factValue())
                    ? FactHistory.REFINED : FactHistory.CONFLICT;
            recordHistory(sessionId, key, old.factValue(), value, sourceTaskId, kind);
            String merged = keepBoth && FactHistory.CONFLICT.equals(kind)
                    ? old.factValue() + " | " + value : value;
            double importance = Math.min(1.0,
                    old.importance() + (FactHistory.CONFLICT.equals(kind) ? 0.2 : 0.1));
            Embedding embedding = embedder.embedOne(key + " " + merged);
            int updated = jdbc.update("""
                            UPDATE ff_session_fact
                               SET fact_value = ?, source_task_id = ?, importance = ?,
                                   status = 'ACTIVE', version = version + 1, embedding = ?,
                                   embedding_model = ?, updated_at = ?
                             WHERE session_id = ? AND fact_key = ? AND version = ?
                            """,
                    merged, sourceTaskId, importance,
                    TextVectorizer.encode(embedding.first()), embedding.model(),
                    now, sessionId, key, old.version());
            if (updated > 0) {
                return Optional.of(new FactWrite(existingId, embedding.model(), embedding.first()));
            }
        }
        log.warn("session.fact.write_contended session={} key={}", sessionId, key);
        return Optional.empty();
    }

    /** Active facts with their row ids, used to (re)build the ANN index. */
    public List<FactRef> factRefs(String sessionId, int limit) {
        return jdbc.query("""
                        SELECT id, fact_key, fact_value FROM ff_session_fact
                         WHERE session_id = ? AND status = 'ACTIVE'
                         ORDER BY updated_at DESC
                         LIMIT ?
                        """,
                (rs, rowNum) -> new FactRef(rs.getString("id"),
                        rs.getString("fact_key"), rs.getString("fact_value")),
                sessionId, Math.max(1, limit));
    }

    /** ACTIVE facts by id, preserving the order of {@code ids} (index hits are ranked). */
    public List<SessionFact> factsByIds(String sessionId, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>(ids.size() + 1);
        args.add(sessionId);
        args.addAll(ids);
        java.util.Map<String, SessionFact> byId = new java.util.HashMap<>();
        jdbc.query("SELECT id, fact_key, fact_value, source_task_id, updated_at, importance, hits, "
                        + "last_used_at, status, version FROM ff_session_fact "
                        + "WHERE session_id = ? AND status = 'ACTIVE' AND id IN (" + placeholders + ")",
                rs -> {
                    byId.put(rs.getString("id"), new SessionFact(
                            rs.getString("fact_key"),
                            rs.getString("fact_value"),
                            rs.getString("source_task_id"),
                            rs.getTimestamp("updated_at").toInstant(),
                            rs.getDouble("importance"),
                            rs.getInt("hits"),
                            rs.getTimestamp("last_used_at") == null ? null
                                    : rs.getTimestamp("last_used_at").toInstant(),
                            rs.getString("status"),
                            rs.getInt("version")));
                }, args.toArray());
        List<SessionFact> ordered = new ArrayList<>(ids.size());
        for (String id : ids) {
            SessionFact fact = byId.get(id);
            if (fact != null) {
                ordered.add(fact);
            }
        }
        return ordered;
    }

    private void recordHistory(String sessionId, String key, String oldValue, String newValue,
                               String sourceTaskId, String kind) {
        jdbc.update("""
                        INSERT INTO ff_session_fact_history
                            (id, session_id, fact_key, old_value, new_value, source_task_id, kind, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                UUID.randomUUID().toString(), sessionId, key, oldValue, newValue,
                sourceTaskId, kind, Timestamp.from(Instant.now()));
    }

    public List<FactHistory> history(String sessionId, String key, int limit) {
        return jdbc.query("""
                        SELECT id, fact_key, old_value, new_value, source_task_id, kind, created_at
                          FROM ff_session_fact_history
                         WHERE session_id = ? AND fact_key = ?
                         ORDER BY created_at DESC
                         LIMIT ?
                        """,
                HISTORY_MAPPER, sessionId, key, Math.max(1, limit));
    }

    /** Active facts, newest first — the candidate pool for recall. */
    public List<SessionFact> facts(String sessionId, int limit) {
        return jdbc.query("""
                        SELECT * FROM ff_session_fact
                         WHERE session_id = ? AND status = 'ACTIVE'
                         ORDER BY updated_at DESC
                         LIMIT ?
                        """,
                FACT_MAPPER, sessionId, Math.max(1, limit));
    }

    /** Active facts together with their stored vectors and model ids, for similarity recall. */
    public List<FactCandidate> candidates(String sessionId, int limit) {
        return jdbc.query("""
                        SELECT * FROM ff_session_fact
                         WHERE session_id = ? AND status = 'ACTIVE'
                         ORDER BY updated_at DESC
                         LIMIT ?
                        """,
                (rs, rowNum) -> new FactCandidate(
                        FACT_MAPPER.mapRow(rs, rowNum),
                        TextVectorizer.decode(rs.getString("embedding")),
                        rs.getString("embedding_model")),
                sessionId, Math.max(1, limit));
    }

    /** Replaces the stored vector after an embedding-provider switch. */
    public void updateEmbedding(String sessionId, String key, float[] vector, String model) {
        jdbc.update("UPDATE ff_session_fact SET embedding = ?, embedding_model = ? "
                        + "WHERE session_id = ? AND fact_key = ?",
                TextVectorizer.encode(vector), model, sessionId, key);
    }

    /** Reinforcement: facts that were actually injected into a prompt age more slowly. */
    public void touch(String sessionId, List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", Collections.nCopies(keys.size(), "?"));
        List<Object> args = new ArrayList<>(keys.size() + 2);
        args.add(Timestamp.from(Instant.now()));
        args.add(sessionId);
        args.addAll(keys);
        jdbc.update("UPDATE ff_session_fact SET hits = hits + 1, last_used_at = ? "
                + "WHERE session_id = ? AND fact_key IN (" + placeholders + ")", args.toArray());
    }

    /**
     * Decay pass: archive facts that are old, rarely recalled and low importance. A fact that
     * has been used at least {@code minHits} times or is important enough survives regardless
     * of age, which is what separates "a name the user gave once" from "a throwaway detail".
     */
    public int archiveStale(Instant cutoff, int minHits, double maxImportance) {
        return jdbc.update("""
                        UPDATE ff_session_fact
                           SET status = 'ARCHIVED'
                         WHERE status = 'ACTIVE'
                           AND COALESCE(last_used_at, updated_at) < ?
                           AND hits < ?
                           AND importance < ?
                        """,
                Timestamp.from(cutoff), minHits, maxImportance);
    }

    /** Bounds a session: keeps the {@code keep} highest-ranked facts active, archives the rest. */
    public int archiveOverflow(String sessionId, int keep) {
        return jdbc.update("""
                        UPDATE ff_session_fact
                           SET status = 'ARCHIVED'
                         WHERE session_id = ?
                           AND status = 'ACTIVE'
                           AND id NOT IN (
                               SELECT id FROM (
                                   SELECT id FROM ff_session_fact
                                    WHERE session_id = ? AND status = 'ACTIVE'
                                    ORDER BY importance DESC, hits DESC, updated_at DESC
                                    LIMIT ?
                               ) AS keep
                           )
                        """,
                sessionId, sessionId, Math.max(1, keep));
    }

    public void deleteFact(String sessionId, String key) {
        jdbc.update("DELETE FROM ff_session_fact WHERE session_id = ? AND fact_key = ?", sessionId, key);
    }

    public List<String> sessionsOfTenant(String tenantId, int limit) {
        return jdbc.query("SELECT id FROM ff_session WHERE tenant_id = ? ORDER BY updated_at DESC LIMIT ?",
                (rs, rowNum) -> rs.getString(1), tenantId, Math.max(1, limit));
    }

    public List<String> allSessionIds(int limit) {
        return jdbc.query("SELECT id FROM ff_session ORDER BY updated_at DESC LIMIT ?",
                (rs, rowNum) -> rs.getString(1), Math.max(1, limit));
    }
}
