package com.forgeflow.worker.sandbox;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SandboxTest {

    /** Container stand-in that never touches a real Docker daemon. */
    static class FakeContainer extends ContainerSandbox {
        private final boolean available;
        final List<String> commands = new ArrayList<>();

        FakeContainer(boolean available) {
            super("alpine:3.20", 2_000, "./target/test-workspace", "docker");
            this.available = available;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public String run(String command) {
            commands.add(command);
            return "container-ran: " + command;
        }

        @Override
        public String describe() {
            return "fake-docker";
        }
    }

    @Test
    void returnsTheToolResult() throws Exception {
        Sandbox sandbox = new Sandbox(true, 1_000, "thread", null);

        assertThat(sandbox.run("echo", () -> "ok")).isEqualTo("ok");
    }

    @Test
    void interruptsAToolThatExceedsItsBudget() {
        Sandbox sandbox = new Sandbox(true, 100, "thread", null);

        assertThatThrownBy(() -> sandbox.run("slow", () -> {
            LockSupport.parkNanos(2_000_000_000L);
            return "late";
        }))
                .isInstanceOf(SandboxTimeoutException.class)
                .hasMessageContaining("slow");
    }

    @Test
    void propagatesToolFailures() {
        Sandbox sandbox = new Sandbox(true, 1_000, "thread", null);

        assertThatThrownBy(() -> sandbox.run("boom", () -> {
            throw new IllegalStateException("tool failed");
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tool failed");
    }

    @Test
    void disabledSandboxRunsInlineWithoutTimeout() throws Exception {
        Sandbox sandbox = new Sandbox(false, 1, "thread", null);

        assertThat(sandbox.run("slow", () -> {
            LockSupport.parkNanos(20_000_000L);
            return "done";
        })).isEqualTo("done");
    }

    @Test
    void autoUsesTheContainerWhenDockerAnswers() throws Exception {
        FakeContainer container = new FakeContainer(true);
        Sandbox sandbox = new Sandbox(true, 1_000, "auto", container);

        assertThat(sandbox.mode()).isEqualTo(Sandbox.Mode.CONTAINER);
        assertThat(sandbox.isolated()).isTrue();
        assertThat(sandbox.runCommand("delete", "rm -f /work/x")).isEqualTo("container-ran: rm -f /work/x");
        assertThat(container.commands).containsExactly("rm -f /work/x");
    }

    @Test
    void autoFallsBackToThreadWhenDockerIsMissing() throws Exception {
        Sandbox sandbox = new Sandbox(true, 1_000, "auto", new FakeContainer(false));

        assertThat(sandbox.mode()).isEqualTo(Sandbox.Mode.THREAD);
        assertThat(sandbox.isolated()).isFalse();
        // Still executes, just without process isolation.
        assertThat(sandbox.runCommand("echo", "echo hi")).contains("hi");
    }

    @Test
    void containerModeFailsClosedWithoutDocker() {
        assertThatThrownBy(() -> new Sandbox(true, 1_000, "container", new FakeContainer(false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refusing to start");
    }

    @Test
    void threadModeNeverProbesDocker() {
        FakeContainer container = new FakeContainer(true);
        Sandbox sandbox = new Sandbox(true, 1_000, "thread", container);

        assertThat(sandbox.mode()).isEqualTo(Sandbox.Mode.THREAD);
        assertThat(container.commands).isEmpty();
    }

    @Test
    void unknownModeIsRejectedAtStartup() {
        assertThatThrownBy(() -> new Sandbox(true, 1_000, "parallel-universe", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forgeflow.sandbox.mode");
    }

    @Test
    void threadModeCommandRespectsTheTimeout() {
        Sandbox sandbox = new Sandbox(true, 200, "thread", null);

        assertThatThrownBy(() -> sandbox.runCommand("slow", sleepCommand()))
                .isInstanceOf(SandboxTimeoutException.class);
    }

    private static String sleepCommand() {
        return System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "ping -n 6 127.0.0.1 > nul"
                : "sleep 5";
    }
}
