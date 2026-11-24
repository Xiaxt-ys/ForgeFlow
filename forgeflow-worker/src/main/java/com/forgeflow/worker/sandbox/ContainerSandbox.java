package com.forgeflow.worker.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Process-level sandbox for dangerous tools: the command runs in a throw-away container with
 * no network, a memory cap, a CPU cap and only the workspace directory mounted.
 *
 * <p>This is a real isolation boundary (unlike the thread/timeout sandbox), at the cost of a
 * container start per call. Whether it is used is decided by {@link Sandbox}: mode {@code auto}
 * picks it whenever a Docker daemon answers, mode {@code container} refuses to start without
 * one, mode {@code thread} never uses it.</p>
 */
@Component
public class ContainerSandbox {

    private static final Logger log = LoggerFactory.getLogger(ContainerSandbox.class);

    private final String image;
    private final long timeoutMs;
    private final String workspace;
    private final String dockerBinary;
    private volatile Boolean available;

    public ContainerSandbox(
            @Value("${forgeflow.sandbox.container.image:alpine:3.20}") String image,
            @Value("${forgeflow.sandbox.container.timeout-ms:10000}") long timeoutMs,
            @Value("${forgeflow.tools.local.workspace:./workspace}") String workspace,
            @Value("${forgeflow.sandbox.container.binary:docker}") String dockerBinary) {
        this.image = image;
        this.timeoutMs = Math.max(1_000L, timeoutMs);
        this.workspace = workspace;
        this.dockerBinary = dockerBinary;
    }

    /** Probed once, then cached: a Docker daemon that is down at boot is not retried per call. */
    public boolean available() {
        Boolean cached = available;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (available == null) {
                available = probe();
                log.info("sandbox.container available={} binary={} image={}",
                        available, dockerBinary, image);
            }
            return available;
        }
    }

    /** Short description for the startup banner, e.g. {@code docker 27.1.1}. */
    public String describe() {
        try {
            Process process = new ProcessBuilder(dockerBinary, "--version")
                    .redirectErrorStream(true).start();
            String output = read(process.getInputStream()).trim();
            if (process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0) {
                return output.replaceFirst("^Docker version ", "").split(",")[0].trim();
            }
            return "docker";
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "docker";
        }
    }

    public String image() {
        return image;
    }

    public String run(String command) {
        if (!available()) {
            throw new IllegalStateException(
                    "container sandbox is required but no Docker daemon is available");
        }
        Path dir = Paths.get(workspace).toAbsolutePath().normalize();
        try {
            Files.createDirectories(dir);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot create sandbox workspace " + dir, failure);
        }
        List<String> docker = List.of(
                dockerBinary, "run", "--rm", "--network", "none",
                "--memory", "128m", "--cpus", "0.5",
                "-v", dir + ":/work",
                image, "sh", "-c", command);
        try {
            Process process = new ProcessBuilder(docker).redirectErrorStream(true).start();
            // Drain on a separate thread: reading inline would block past the timeout.
            java.util.concurrent.CompletableFuture<String> reader = java.util.concurrent.CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            return read(process.getInputStream());
                        } catch (IOException failure) {
                            return "";
                        }
                    });
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                reader.cancel(true);
                throw new SandboxTimeoutException("container", timeoutMs);
            }
            String output;
            try {
                output = reader.get(2, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException
                     failure) {
                output = "";
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("container command failed (" + process.exitValue()
                        + "): " + output.trim());
            }
            log.info("sandbox.container command={} exit=0 output={}", command, output.trim());
            return output.trim();
        } catch (IOException failure) {
            throw new IllegalStateException("docker is not available for the container sandbox",
                    failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while running the container sandbox",
                    interrupted);
        }
    }

    private boolean probe() {
        try {
            Process process = new ProcessBuilder(dockerBinary, "info", "--format", "{{.ServerVersion}}")
                    .redirectErrorStream(true).start();
            String output = read(process.getInputStream()).trim();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0 && !output.isEmpty();
        } catch (IOException failure) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String read(InputStream input) throws IOException {
        try (input) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
