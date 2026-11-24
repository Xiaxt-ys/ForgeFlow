package com.forgeflow.store;

import java.util.List;

/** Read model for the session endpoint and for the entry agent's context block. */
public record SessionSnapshot(String sessionId, String tenantId, String summary,
                              List<SessionFact> facts) {
}
