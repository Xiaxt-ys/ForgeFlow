package com.forgeflow.store;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "forgeflow.tenant")
public class TenantQuotaProperties {

    /**
     * Maximum concurrently running tasks per tenant. 0 or negative disables the gate.
     */
    private int maxInflight = 20;
    /**
     * Redis counter TTL, so a crashed worker cannot hold a tenant slot forever.
     */
    private long keyTtlSeconds = 3600;

    public int getMaxInflight() {
        return maxInflight;
    }

    public void setMaxInflight(int maxInflight) {
        this.maxInflight = maxInflight;
    }

    public long getKeyTtlSeconds() {
        return keyTtlSeconds;
    }

    public void setKeyTtlSeconds(long keyTtlSeconds) {
        this.keyTtlSeconds = keyTtlSeconds;
    }
}
