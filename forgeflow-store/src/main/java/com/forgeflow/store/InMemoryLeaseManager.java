package com.forgeflow.store;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local-demo lease. It has the same fencing semantics but no cross-process visibility.
 */
@Component
@ConditionalOnProperty(name = "forgeflow.lease.mode", havingValue = "memory")
public class InMemoryLeaseManager implements LeaseManager {

    private final ConcurrentHashMap<String, Lease> leases = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(String taskId, String owner, Duration ttl) {
        Instant now = Instant.now();
        Lease lease = new Lease(owner, now.plus(ttl));
        Lease existing = leases.putIfAbsent(taskId, lease);
        if (existing == null) {
            return true;
        }
        if (existing.expiresAt().isAfter(now)) {
            return false;
        }
        return leases.replace(taskId, existing, lease);
    }

    @Override
    public boolean renew(String taskId, String owner, Duration ttl) {
        Instant now = Instant.now();
        Lease lease = new Lease(owner, now.plus(ttl));
        Lease existing = leases.get(taskId);
        if (existing == null || !existing.owner().equals(owner) || existing.expiresAt().isBefore(now)) {
            return false;
        }
        return leases.replace(taskId, existing, lease);
    }

    @Override
    public void release(String taskId, String owner) {
        leases.computeIfPresent(taskId, (id, existing) -> existing.owner().equals(owner) ? null : existing);
    }

    private record Lease(String owner, Instant expiresAt) {
    }
}
