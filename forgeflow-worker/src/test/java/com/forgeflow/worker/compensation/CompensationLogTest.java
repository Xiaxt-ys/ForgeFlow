package com.forgeflow.worker.compensation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompensationLogTest {

    @Test
    void undoesActionsInReverseOrder() {
        CompensationLog log = new CompensationLog();
        List<String> order = new ArrayList<>();
        log.record("t1", new Compensation("first", "one", () -> order.add("first")));
        log.record("t1", new Compensation("second", "two", () -> order.add("second")));

        List<String> done = log.compensateAll("t1");

        assertThat(order).containsExactly("second", "first");
        assertThat(done).containsExactly("second: two", "first: one");
        assertThat(log.size("t1")).isZero();
    }

    @Test
    void failingUndoDoesNotStopTheRest() {
        CompensationLog log = new CompensationLog();
        List<String> order = new ArrayList<>();
        log.record("t1", new Compensation("ok", "fine", () -> order.add("ok")));
        log.record("t1", new Compensation("boom", "explodes", () -> {
            throw new IllegalStateException("undo failed");
        }));

        List<String> done = log.compensateAll("t1");

        assertThat(order).containsExactly("ok");
        assertThat(done).containsExactly("boom: FAILED", "ok: fine");
    }

    @Test
    void clearDropsPendingActions() {
        CompensationLog log = new CompensationLog();
        List<String> order = new ArrayList<>();
        log.record("t1", new Compensation("write_note", "remove", () -> order.add("undo")));

        log.clear("t1");

        assertThat(log.compensateAll("t1")).isEmpty();
        assertThat(order).isEmpty();
    }

    @Test
    void tasksAreIsolated() {
        CompensationLog log = new CompensationLog();
        List<String> order = new ArrayList<>();
        log.record("t1", new Compensation("a", "a", () -> order.add("t1")));
        log.record("t2", new Compensation("b", "b", () -> order.add("t2")));

        log.compensateAll("t1");

        assertThat(order).containsExactly("t1");
        assertThat(log.size("t2")).isEqualTo(1);
    }
}
