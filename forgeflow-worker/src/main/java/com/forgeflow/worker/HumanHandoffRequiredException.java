package com.forgeflow.worker;

/**
 * Signals that the turn cannot produce a usable result on its own and a human should decide.
 * The worker parks the task in WAITING_HUMAN instead of failing it.
 */
public class HumanHandoffRequiredException extends RuntimeException {

    public HumanHandoffRequiredException(String message) {
        super(message);
    }

    public HumanHandoffRequiredException(String message, Throwable cause) {
        super(message, cause);
    }
}
