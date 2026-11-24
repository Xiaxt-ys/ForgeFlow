package com.forgeflow.common;

public class TenantQuotaExceededException extends RuntimeException {
    public TenantQuotaExceededException(String tenantId) {
        super("tenant quota exceeded: " + tenantId);
    }
}
