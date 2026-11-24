package com.forgeflow.worker.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Execution gate for dangerous tools, with an explicit isolation mode:
 *
 * <ul>
 *   <li>{@code auto} (default) — use the container sandbox whenever a Docker daemon answers,
 *       otherwise fall back to the thread/timeout sandbox and say so loudly at startup;</li>
 *   <li>{@code container} — demand real process isolation: refuse to start when Docker is
 *       missing, and never let a dangerous command run on the host;</li>
 *   <li>{@code thread} — the old behaviour: a dedicated thread with an empty task context and a
 *       timeout. Cheap, but it confines neither filesystem nor network.</li>
 * </ul>
 *
 * <p>A tool that expresses its effect as a shell command calls {@link #runCommand}; a tool that
 * is an in-process Java action calls {@link #run}. In container mode {@code run} still executes
 * in-process (the action is Java code, a container cannot host it) — {@link #isolated()} tells
 * such tools to route their side effect through {@link #runCommand} instead.</p>
 */
@Component
public class Sandbox {

    private static final Logger log = LoggerFactory.getLogger(Sandbox.class);

    public enum Mode {
        AUTO, CONTAINER, THREAD;

        static Mode parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return AUTO;
            }
            try {
                return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                throw new IllegalArgumentException(
                        "unknown forgeflow.sandbox.mode '" + raw + "': use auto, container or thread");
            }
        }
    }

    private final boolean enabled;
    private final long timeoutMs;
    private final Mode requested;
    private final Mode resolved;
    private final ContainerSandbox container;
    private final ExecutorService pool = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "ff-sandbox");
        thread.setDaemon(true);
        return thread;
    });

    @Autowired
    public Sandbox(@Value("${forgeflow.sandbox.enabled:true}") boolean enabled,
                   @Value("${forgeflow.sandbox.timeout-ms:10000}") long timeoutMs,
                   @Value("${forgeflow.sandbox.mode:auto}") String mode,
                   ContainerSandbox container) {
        this(enabled, timeoutMs, Mode.parse(mode), container);
    }

    Sandbox(boolean enabled, long timeoutMs, Mode requested, ContainerSandbox container) {
        this.enabled = enabled;
        this.timeoutMs = Math.max(1, timeoutMs);
        this.requested = requested;
        this.container = container;
        this.resolved = resolve(requested, container);
        if (resolved == Mode.CONTAINER) {
            log.info("sandbox.mode requested={} resolved=container docker={} image={}",
                    requested, container.describe(), container.image());
        } else if (requested == Mode.THREAD || !enabled) {
            log.info("sandbox.mode requested={} resolved=thread (dangerous tools are NOT "
                    + "process-isolated)", requested);
        } else {
            log.warn("sandbox.mode requested={} resolved=thread: no Docker daemon answered, so "
                    + "dangerous tools are NOT process-isolated. Start Docker or set "
                    + "forgeflow.sandbox.mode=container to fail closed.", requested);
        }
    }

    private static Mode resolve(Mode requested, ContainerSandbox container) {
        if (!requested.equals(Mode.AUTO) && !requested.equals(Mode.CONTAINER)) {
            return Mode.THREAD;
        }
        boolean available = container != null && container.available();
        if (requested == Mode.CONTAINER && !available) {
            throw new IllegalStateException(
                    "forgeflow.sandbox.mode=container but no Docker daemon is available; "
                            + "refusing to start rather than run dangerous tools on the host");
        }
        return available ? Mode.CONTAINER : Mode.THREAD;
    }

    /** True when dangerous side effects must go through {@link #runCommand}. */
    public boolean isolated() {
        return enabled && resolved == Mode.CONTAINER;
    }

    public Mode mode() {
        return resolved;
    }

    public Mode requestedMode() {
        return requested;
    }

    /** In-process actions: always run on the sandbox thread, whatever the mode. */
    public Object run(String toolName, Callable<Object> action) throws Exception {
        if (!enabled) {
            return action.call();
        }
        Future<Object> future = pool.submit(action);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            log.warn("sandbox.timeout tool={} timeoutMs={}", toolName, timeoutMs);
            throw new SandboxTimeoutException(toolName, timeoutMs);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while running tool " + toolName, interrupted);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof Exception checked) {
                throw checked;
            }
            throw new IllegalStateException("sandboxed tool " + toolName + " failed", cause);
        }
    }

    /**
     * Shell effects: in container mode they execute inside the throw-away container (no
     * network, 128 MB, 0.5 CPU, only the workspace mounted); in thread mode they execute on
     * the sandbox thread with the same timeout, i.e. without filesystem or network isolation.
     */
    public String runCommand(String toolName, String command) throws Exception {
        if (isolated()) {
            log.info("sandbox.dispatch tool={} mode=container", toolName);
            return container.run(command);
        }
        log.info("sandbox.dispatch tool={} mode=thread", toolName);
        Object result = run(toolName, () -> execute(command));
        return result == null ? "" : result.toString();
    }

    private String execute(String command) throws IOException, InterruptedException {
        List<String> argv = isWindows()
                ? List.of("cmd.exe", "/c", command)
                : List.of("sh", "-c", command);
        Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
        // Drain on a separate thread, otherwise a silent command blocks past its timeout.
        Future<String> reader = pool.submit(() -> {
            try (InputStream input = process.getInputStream()) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        });
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            reader.cancel(true);
            throw new SandboxTimeoutException("thread", timeoutMs);
        }
        String output;
        try {
            output = reader.get(2, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException failure) {
            output = "";
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("sandboxed command failed (" + process.exitValue()
                    + "): " + output.trim());
        }
        return output.trim();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}
