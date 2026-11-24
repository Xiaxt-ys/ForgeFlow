package com.forgeflow.common;

public class NoAvailableModelException extends RuntimeException {
    public NoAvailableModelException(String message) {
        super(message);
    }

    public NoAvailableModelException(String message, Throwable cause) {
        super(message, cause);
    }
}
