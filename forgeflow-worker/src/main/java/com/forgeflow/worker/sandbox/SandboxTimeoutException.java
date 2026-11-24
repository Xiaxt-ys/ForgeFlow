package com.forgeflow.worker.sandbox;

/**
 * A dangerous tool exceeded its sandbox budget and was interrupted.
 */
public class SandboxTimeoutException extends RuntimeException {

    public SandboxTimeoutException(String tool, long timeoutMs) {
        super("sandbox timeout after " + timeoutMs + "ms running dangerous tool " + tool);
    }
}
