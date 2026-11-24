package com.forgeflow.store;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "forgeflow.tenant.mode", havingValue = "redis")
public class RedisTenantQuotaStore implements TenantQuotaStore {

    private static final String PREFIX = "ff:tenant:inflight:";

    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>(
            "local current = tonumber(redis.call('get', KEYS[1]) or '0') "
                    + "if current >= tonumber(ARGV[1]) then return 0 end "
                    + "redis.call('incr', KEYS[1]) "
                    + "redis.call('expire', KEYS[1], ARGV[2]) "
                    + "return 1",
            Long.class);

    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "local current = tonumber(redis.call('get', KEYS[1]) or '0') "
                    + "if current <= 0 then return 0 end "
                    + "return redis.call('decr', KEYS[1])",
            Long.class);

    private final StringRedisTemplate redis;
    private final TenantQuotaProperties properties;

    public RedisTenantQuotaStore(StringRedisTemplate redis, TenantQuotaProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public boolean tryAcquire(String tenantId) {
        if (properties.getMaxInflight() <= 0) {
            return true;
        }
        Long result = redis.execute(ACQUIRE, List.of(key(tenantId)),
                String.valueOf(properties.getMaxInflight()),
                String.valueOf(properties.getKeyTtlSeconds()));
        return result != null && result == 1L;
    }

    @Override
    public void release(String tenantId) {
        redis.execute(RELEASE, List.of(key(tenantId)));
    }

    private String key(String tenantId) {
        return PREFIX + tenantId;
    }
}
