package com.forgeflow.store;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
@ConditionalOnProperty(name = "forgeflow.lease.mode", havingValue = "redis", matchIfMissing = true)
public class RedisLeaseManager implements LeaseManager {

    private static final String PREFIX = "ff:task:lease:";

    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end",
            Long.class);

    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;

    public RedisLeaseManager(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean tryAcquire(String taskId, String owner, Duration ttl) {
        Boolean acquired = redis.opsForValue().setIfAbsent(key(taskId), owner, ttl);
        return Boolean.TRUE.equals(acquired);
    }

    @Override
    public boolean renew(String taskId, String owner, Duration ttl) {
        Long result = redis.execute(RENEW, List.of(key(taskId)), owner,
                String.valueOf(ttl.toMillis()));
        return result != null && result == 1L;
    }

    @Override
    public void release(String taskId, String owner) {
        redis.execute(RELEASE, List.of(key(taskId)), owner);
    }

    private String key(String taskId) {
        return PREFIX + taskId;
    }
}
