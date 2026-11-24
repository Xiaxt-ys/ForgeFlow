package com.forgeflow.store;

import java.time.Duration;

/**
 * Worker lease. The lease answers "who may execute now"; DB version predicates answer
 * "whose write wins". Both are required: leases reduce duplicate work, fencing prevents
 * a resurrected worker from committing stale state.
 */
public interface LeaseManager {
    boolean tryAcquire(String taskId, String owner, Duration ttl);

    boolean renew(String taskId, String owner, Duration ttl);

    void release(String taskId, String owner);
}
