package com.forgeflow.store;

public interface TenantQuotaStore {

    boolean tryAcquire(String tenantId);

    void release(String tenantId);
}
