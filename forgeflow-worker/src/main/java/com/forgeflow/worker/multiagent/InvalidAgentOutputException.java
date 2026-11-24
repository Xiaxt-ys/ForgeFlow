package com.forgeflow.worker.multiagent;

/**
 * Thrown when an agent output fails structural checks. The worker turns it into a FAILED
 * task, so a model that claims success without a usable result is not recorded as done.
 */
public class InvalidAgentOutputException extends RuntimeException {

    public InvalidAgentOutputException(String message) {
        super(message);
    }
}
