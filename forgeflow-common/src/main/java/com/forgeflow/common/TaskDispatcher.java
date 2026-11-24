package com.forgeflow.common;

/**
 * Dispatch abstraction: RabbitMQ in production, in-process executor for local demos/tests.
 */
public interface TaskDispatcher {
    void dispatch(TaskMessage message);
}
