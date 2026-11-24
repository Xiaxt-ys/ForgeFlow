package com.forgeflow.store;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@ConditionalOnProperty(name = "forgeflow.tenant.mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryTenantQuotaStore implements TenantQuotaStore {

    private final TenantQuotaProperties properties;
    private final ConcurrentHashMap<String, AtomicInteger> inflight = new ConcurrentHashMap<>();

    public InMemoryTenantQuotaStore(TenantQuotaProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean tryAcquire(String tenantId) {
        if (properties.getMaxInflight() <= 0) {
            return true;
        }
        AtomicInteger counter = inflight.computeIfAbsent(tenantId, id -> new AtomicInteger());
        while (true) {
            int current = counter.get();
            if (current >= properties.getMaxInflight()) {
                return false;
            }
            if (counter.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    @Override
    public void release(String tenantId) {
        AtomicInteger counter = inflight.get(tenantId);
        if (counter != null) {
            counter.updateAndGet(value -> Math.max(0, value - 1));
        }
    }
}
