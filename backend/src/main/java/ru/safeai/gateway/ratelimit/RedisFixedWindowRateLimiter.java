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

    private static final DefaultRedisScript<String> INCREMENT_WITH_TTL_SCRIPT;

    static {
        INCREMENT_WITH_TTL_SCRIPT = new DefaultRedisScript<>();
        INCREMENT_WITH_TTL_SCRIPT.setResultType(String.class);
        INCREMENT_WITH_TTL_SCRIPT.setScriptText("""
                local current = redis.call('INCR', KEYS[1])
                local ttl = redis.call('PTTL', KEYS[1])

                if ttl < 0 then
                    redis.call('PEXPIRE', KEYS[1], ARGV[1])
                    ttl = tonumber(ARGV[1])
                end

                return tostring(current) .. ':' .. tostring(ttl)
                """);
    }

    private final StringRedisTemplate redisTemplate;

    public RateLimitResult incrementAndGet(String key, Duration ttl) {
        Objects.requireNonNull(key, "key не должен быть null");
        Objects.requireNonNull(ttl, "ttl не должен быть null");

        if (key.isBlank()) {
            throw new IllegalArgumentException("key не должен быть пустым");
        }

        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl должен быть положительным");
        }

        String result = redisTemplate.execute(
                INCREMENT_WITH_TTL_SCRIPT,
                List.of(key),
                String.valueOf(ttl.toMillis())
        );

        if (result == null || result.isBlank()) {
            throw new IllegalStateException("Redis Lua script returned invalid result");
        }

        String[] parts = result.split(":");

        if (parts.length != 2) {
            throw new IllegalStateException("Redis Lua script returned invalid result: " + result);
        }

        long count = Long.parseLong(parts[0]);
        long ttlMillis = Long.parseLong(parts[1]);
        long ttlSeconds = Math.max(1, (long) Math.ceil(ttlMillis / 1000.0));

        return new RateLimitResult(count, ttlSeconds);
    }

    public void reset(String key) {
        Objects.requireNonNull(key, "key не должен быть null");

        if (key.isBlank()) {
            throw new IllegalArgumentException("key не должен быть пустым");
        }

        redisTemplate.delete(key);
    }
}