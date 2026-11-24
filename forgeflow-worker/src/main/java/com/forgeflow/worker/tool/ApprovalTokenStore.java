package com.forgeflow.worker.tool;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory approval tickets. A ticket is bound to task + turn + tool + canonical args hash;
 * changing any of them invalidates it.
 */
public class ApprovalTokenStore {

    private final ApprovalTokenService tokens;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public ApprovalTokenStore(ApprovalTokenService tokens) {
        this.tokens = tokens;
    }

    public ApprovalToken issue(String taskId, String turnId, String toolName, String argsHash,
                               Duration ttl) {
        Instant expiresAt = Instant.now().plus(ttl);
        ToolCall call = new ToolCall(taskId, turnId, toolName, argsHash);
        ApprovalToken token = tokens.issue(call, expiresAt);
        entries.put(key(taskId, turnId, toolName, argsHash), new Entry(token, expiresAt));
        return token;
    }

    public boolean verify(String taskId, String turnId, String toolName, String argsHash) {
        String key = key(taskId, turnId, toolName, argsHash);
        Entry entry = entries.get(key);
        if (entry == null) {
            return false;
        }
        if (entry.expiresAt().isBefore(Instant.now())) {
            entries.remove(key);
            return false;
        }
        return tokens.verify(entry.token(),
                new ToolCall(taskId, turnId, toolName, argsHash));
    }

    public Optional<ApprovalToken> find(String taskId, String turnId, String toolName,
                                        String argsHash) {
        return verify(taskId, turnId, toolName, argsHash)
                ? Optional.of(entries.get(key(taskId, turnId, toolName, argsHash)).token())
                : Optional.empty();
    }

    private String key(String taskId, String turnId, String toolName, String argsHash) {
        return taskId + "|" + turnId + "|" + toolName + "|" + argsHash;
    }

    private record Entry(ApprovalToken token, Instant expiresAt) {
    }
}
