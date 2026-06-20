package ru.safeai.gateway.ratelimit;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class RedisFixedWindowRateLimiter {

    private static final DefaultRedisScript<Long> INCREMENT_WITH_TTL_SCRIPT;

    static {
        INCREMENT_WITH_TTL_SCRIPT = new DefaultRedisScript<>();
        INCREMENT_WITH_TTL_SCRIPT.setResultType(Long.class);
        INCREMENT_WITH_TTL_SCRIPT.setScriptText("""
                local current = redis.call('INCR', KEYS[1])
                local ttl = redis.call('PTTL', KEYS[1])

                if ttl < 0 then
                    redis.call('PEXPIRE', KEYS[1], ARGV[1])
                end

                return current
                """);
    }

    private final StringRedisTemplate redisTemplate;

    public long incrementAndGet(String key, Duration ttl) {
        Objects.requireNonNull(key, "key не должен быть null");
        Objects.requireNonNull(ttl, "ttl не должен быть null");

        if (key.isBlank()) {
            throw new IllegalArgumentException("key не должен быть пустым");
        }

        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl должен быть положительным");
        }

        Long count = redisTemplate.execute(
                INCREMENT_WITH_TTL_SCRIPT,
                List.of(key),
                String.valueOf(ttl.toMillis())
        );

        if (count == null) {
            throw new IllegalStateException("Redis Lua script returned null");
        }

        return count;
    }

    public void reset(String key) {
        Objects.requireNonNull(key, "key не должен быть null");

        if (key.isBlank()) {
            throw new IllegalArgumentException("key не должен быть пустым");
        }

        redisTemplate.delete(key);
    }
}