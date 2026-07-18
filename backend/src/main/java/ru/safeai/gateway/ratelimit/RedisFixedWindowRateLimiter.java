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

    private static final DefaultRedisScript<String>
            TRY_INCREMENT_BOTH_SCRIPT;

    private static final DefaultRedisScript<String>
            INCREMENT_BOTH_AND_CHECK_SCRIPT;

    static {
        /*
         * AI rate limit:
         * оба счётчика увеличиваются только тогда, когда оба лимита
         * разрешают выполнение запроса.
         *
         * KEYS[1] — user key
         * KEYS[2] — organization key
         * ARGV[1] — TTL в миллисекундах
         * ARGV[2] — user limit
         * ARGV[3] — organization limit
         */
        TRY_INCREMENT_BOTH_SCRIPT = new DefaultRedisScript<>();
        TRY_INCREMENT_BOTH_SCRIPT.setResultType(String.class);
        TRY_INCREMENT_BOTH_SCRIPT.setScriptText("""
                local ttlMs = tonumber(ARGV[1])
                local firstLimit = tonumber(ARGV[2])
                local secondLimit = tonumber(ARGV[3])

                local firstCurrent =
                    tonumber(redis.call('GET', KEYS[1]) or '0')

                local secondCurrent =
                    tonumber(redis.call('GET', KEYS[2]) or '0')

                local firstTtl = redis.call('PTTL', KEYS[1])
                local secondTtl = redis.call('PTTL', KEYS[2])

                if firstCurrent > 0 and firstTtl < 0 then
                    redis.call('PEXPIRE', KEYS[1], ttlMs)
                    firstTtl = ttlMs
                end

                if secondCurrent > 0 and secondTtl < 0 then
                    redis.call('PEXPIRE', KEYS[2], ttlMs)
                    secondTtl = ttlMs
                end

                local firstExceeded =
                    firstCurrent >= firstLimit

                local secondExceeded =
                    secondCurrent >= secondLimit

                if firstExceeded or secondExceeded then
                    local decision = 'BOTH_EXCEEDED'

                    if firstExceeded and not secondExceeded then
                        decision = 'FIRST_EXCEEDED'
                    elseif secondExceeded and not firstExceeded then
                        decision = 'SECOND_EXCEEDED'
                    end

                    local notification = 0
                    local markerKey = KEYS[1] .. ':exceeded'
                    local markerTtl = firstTtl

                    if secondExceeded and not firstExceeded then
                        markerKey = KEYS[2] .. ':exceeded'
                        markerTtl = secondTtl
                    end

                    if markerTtl < 1 then
                        markerTtl = ttlMs
                    end

                    local markerResult = redis.call(
                        'SET',
                        markerKey,
                        '1',
                        'PX',
                        markerTtl,
                        'NX'
                    )

                    if markerResult then
                        notification = 1
                    end

                    return decision
                        .. ':' .. tostring(firstCurrent)
                        .. ':' .. tostring(secondCurrent)
                        .. ':' .. tostring(firstTtl)
                        .. ':' .. tostring(secondTtl)
                        .. ':' .. tostring(notification)
                end

                local newFirst =
                    redis.call('INCR', KEYS[1])

                local newSecond =
                    redis.call('INCR', KEYS[2])

                firstTtl = redis.call('PTTL', KEYS[1])
                secondTtl = redis.call('PTTL', KEYS[2])

                if firstTtl < 0 then
                    redis.call('PEXPIRE', KEYS[1], ttlMs)
                    firstTtl = ttlMs
                end

                if secondTtl < 0 then
                    redis.call('PEXPIRE', KEYS[2], ttlMs)
                    secondTtl = ttlMs
                end

                return 'ALLOWED'
                    .. ':' .. tostring(newFirst)
                    .. ':' .. tostring(newSecond)
                    .. ':' .. tostring(firstTtl)
                    .. ':' .. tostring(secondTtl)
                    .. ':0'
                """);

        /*
         * Login rate limit:
         * оба счётчика всегда увеличиваются атомарно.
         *
         * KEYS[1] — email key
         * KEYS[2] — IP key
         * ARGV[1] — TTL в миллисекундах
         * ARGV[2] — email limit
         * ARGV[3] — IP limit
         */
        INCREMENT_BOTH_AND_CHECK_SCRIPT =
                new DefaultRedisScript<>();

        INCREMENT_BOTH_AND_CHECK_SCRIPT.setResultType(
                String.class
        );

        INCREMENT_BOTH_AND_CHECK_SCRIPT.setScriptText("""
                local ttlMs = tonumber(ARGV[1])
                local firstLimit = tonumber(ARGV[2])
                local secondLimit = tonumber(ARGV[3])

                local newFirst =
                    redis.call('INCR', KEYS[1])

                local newSecond =
                    redis.call('INCR', KEYS[2])

                local firstTtl =
                    redis.call('PTTL', KEYS[1])

                local secondTtl =
                    redis.call('PTTL', KEYS[2])

                if firstTtl < 0 then
                    redis.call('PEXPIRE', KEYS[1], ttlMs)
                    firstTtl = ttlMs
                end

                if secondTtl < 0 then
                    redis.call('PEXPIRE', KEYS[2], ttlMs)
                    secondTtl = ttlMs
                end

                local firstExceeded =
                    newFirst > firstLimit

                local secondExceeded =
                    newSecond > secondLimit

                local decision = 'ALLOWED'

                if firstExceeded and secondExceeded then
                    decision = 'BOTH_EXCEEDED'
                elseif firstExceeded then
                    decision = 'FIRST_EXCEEDED'
                elseif secondExceeded then
                    decision = 'SECOND_EXCEEDED'
                end

                return decision
                    .. ':' .. tostring(newFirst)
                    .. ':' .. tostring(newSecond)
                    .. ':' .. tostring(firstTtl)
                    .. ':' .. tostring(secondTtl)
                    .. ':0'
                """);
    }

    private final StringRedisTemplate redisTemplate;

    public DualRateLimitResult tryIncrementBoth(
            String firstKey,
            int firstLimit,
            String secondKey,
            int secondLimit,
            Duration ttl
    ) {
        return executeDualScript(
                TRY_INCREMENT_BOTH_SCRIPT,
                firstKey,
                firstLimit,
                secondKey,
                secondLimit,
                ttl
        );
    }

    public DualRateLimitResult incrementBothAndCheck(
            String firstKey,
            int firstLimit,
            String secondKey,
            int secondLimit,
            Duration ttl
    ) {
        return executeDualScript(
                INCREMENT_BOTH_AND_CHECK_SCRIPT,
                firstKey,
                firstLimit,
                secondKey,
                secondLimit,
                ttl
        );
    }

    public void reset(String key) {
        Objects.requireNonNull(
                key,
                "key не должен быть null"
        );

        if (key.isBlank()) {
            throw new IllegalArgumentException(
                    "key не должен быть пустым"
            );
        }

        redisTemplate.delete(key);
        redisTemplate.delete(key + ":exceeded");
    }

    private DualRateLimitResult executeDualScript(
            DefaultRedisScript<String> script,
            String firstKey,
            int firstLimit,
            String secondKey,
            int secondLimit,
            Duration ttl
    ) {
        validateKeyAndTtl(firstKey, ttl);
        validateKeyAndTtl(secondKey, ttl);

        validateLimit(firstLimit, "firstLimit");
        validateLimit(secondLimit, "secondLimit");

        String result = redisTemplate.execute(
                script,
                List.of(firstKey, secondKey),
                String.valueOf(ttl.toMillis()),
                String.valueOf(firstLimit),
                String.valueOf(secondLimit)
        );

        if (result == null || result.isBlank()) {
            throw new IllegalStateException(
                    "Redis dual Lua script returned invalid result"
            );
        }

        String[] parts = result.split(":", -1);

        if (parts.length != 6) {
            throw new IllegalStateException(
                    "Redis dual Lua script returned invalid result: "
                            + result
            );
        }

        RateLimitDecision decision =
                parseDecision(parts[0], result);

        return new DualRateLimitResult(
                decision,
                parseNonNegativeLong(parts[1], result),
                parseNonNegativeLong(parts[2], result),
                toSeconds(parseTtlMillis(parts[3], result)),
                toSeconds(parseTtlMillis(parts[4], result)),
                parseNotificationFlag(parts[5], result)
        );
    }

    private RateLimitDecision parseDecision(
            String value,
            String rawResult
    ) {
        try {
            return RateLimitDecision.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Redis dual Lua script returned invalid decision: "
                            + rawResult,
                    exception
            );
        }
    }

    private boolean parseNotificationFlag(
            String value,
            String rawResult
    ) {
        return switch (value) {
            case "0" -> false;
            case "1" -> true;
            default -> throw new IllegalStateException(
                    "Redis dual Lua script returned invalid "
                            + "notification flag: "
                            + rawResult
            );
        };
    }

    private void validateKeyAndTtl(
            String key,
            Duration ttl
    ) {
        Objects.requireNonNull(
                key,
                "key не должен быть null"
        );

        Objects.requireNonNull(
                ttl,
                "ttl не должен быть null"
        );

        if (key.isBlank()) {
            throw new IllegalArgumentException(
                    "key не должен быть пустым"
            );
        }

        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException(
                    "ttl должен быть положительным"
            );
        }
    }

    private void validateLimit(
            int limit,
            String name
    ) {
        if (limit < 1) {
            throw new IllegalArgumentException(
                    name + " должен быть положительным"
            );
        }
    }

    private long parseNonNegativeLong(
            String value,
            String rawResult
    ) {
        try {
            long parsed = Long.parseLong(value);

            if (parsed < 0) {
                throw new IllegalStateException(
                        "Redis returned negative counter: "
                                + rawResult
                );
            }

            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(
                    "Redis returned non-numeric value: "
                            + rawResult,
                    exception
            );
        }
    }

    private long parseTtlMillis(
            String value,
            String rawResult
    ) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(
                    "Redis returned invalid TTL: "
                            + rawResult,
                    exception
            );
        }
    }

    private long toSeconds(long ttlMillis) {
        if (ttlMillis <= 0) {
            return 1L;
        }

        return Math.max(
                1L,
                (long) Math.ceil(ttlMillis / 1000.0)
        );
    }
}