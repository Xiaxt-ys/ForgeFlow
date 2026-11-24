package com.forgeflow.worker;

import com.forgeflow.worker.tool.ApprovalTokenService;
import com.forgeflow.worker.tool.ApprovalTokenStore;
import com.forgeflow.worker.tool.CanonicalJson;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalTokenStoreTest {

    private final ApprovalTokenStore store =
            new ApprovalTokenStore(new ApprovalTokenService("test-secret"));

    @Test
    void tokenIsBoundToTaskTurnToolAndArgs() {
        store.issue("task-1", "turn-1", "refund", "hash-a", Duration.ofSeconds(60));

        assertThat(store.verify("task-1", "turn-1", "refund", "hash-a")).isTrue();
        assertThat(store.verify("task-1", "turn-1", "refund", "hash-b")).isFalse();
        assertThat(store.verify("task-2", "turn-1", "refund", "hash-a")).isFalse();
        assertThat(store.verify("task-1", "turn-2", "refund", "hash-a")).isFalse();
    }

    @Test
    void expiredTokenIsRejected() {
        store.issue("task-1", "turn-1", "refund", "hash-a", Duration.ofMillis(-1));
        assertThat(store.verify("task-1", "turn-1", "refund", "hash-a")).isFalse();
    }

    @Test
    void canonicalJsonIgnoresMapOrder() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("b", 2);
        first.put("a", 1);

        Map<String, Object> second = new LinkedHashMap<>();
        second.put("a", 1);
        second.put("b", 2);

        assertThat(CanonicalJson.of(first)).isEqualTo(CanonicalJson.of(second));
    }
}
