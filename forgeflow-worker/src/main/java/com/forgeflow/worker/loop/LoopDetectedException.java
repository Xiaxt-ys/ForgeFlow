package com.forgeflow.worker.loop;

/**
 * Raised when the loop guard detects a non-progressing agent loop. The worker turns it into
 * a FAILED task instead of letting the turn run until the model gives up.
 */
public class LoopDetectedException extends RuntimeException {

    public LoopDetectedException(String signal, String detail) {
        super("loop detected [" + signal + "]: " + detail);
    }
}
