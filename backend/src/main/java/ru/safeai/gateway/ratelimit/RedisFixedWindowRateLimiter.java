package ru.safeai.gateway.ratelimit;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class RedisFixedWindowRateLimiter {

    private static final DefaultRedisScript<String>
            INCREMENT_ONE_SCRIPT = script("""
            local ttlMs = tonumber(ARGV[1])

            local current = redis.call(
                'INCR',
                KEYS[1]
            )

            local ttl = redis.call(
                'PTTL',
                KEYS[1]
            )

            if ttl < 0 then
                redis.call(
                    'PEXPIRE',
                    KEYS[1],
                    ttlMs
                )

                ttl = ttlMs
            elseif ttl == 0 then
                ttl = 1
            end

            return tostring(current)
                .. ':'
                .. tostring(ttl)
            """);

    private static final DefaultRedisScript<String>
            TRY_INCREMENT_BOTH_SCRIPT = script("""
            local ttlMs = tonumber(ARGV[1])
            local firstLimit = tonumber(ARGV[2])
            local secondLimit = tonumber(ARGV[3])

            local firstCurrent =
                tonumber(redis.call('GET', KEYS[1]) or '0')

            local secondCurrent =
                tonumber(redis.call('GET', KEYS[2]) or '0')

            local firstTtl =
                redis.call('PTTL', KEYS[1])

            local secondTtl =
                redis.call('PTTL', KEYS[2])

            if firstCurrent > 0 and firstTtl < 0 then
                redis.call(
                    'PEXPIRE',
                    KEYS[1],
                    ttlMs
                )

                firstTtl = ttlMs
            end

            if secondCurrent > 0 and secondTtl < 0 then
                redis.call(
                    'PEXPIRE',
                    KEYS[2],
                    ttlMs
                )

                secondTtl = ttlMs
            end

            local firstExceeded =
                firstCurrent >= firstLimit

            local secondExceeded =
                secondCurrent >= secondLimit

            if firstExceeded or secondExceeded then
                local firstNotification = 0
                local secondNotification = 0

                if firstExceeded then
                    local markerTtl = firstTtl

                    if markerTtl < 1 then
                        markerTtl = ttlMs
                    end

                    local markerResult = redis.call(
                        'SET',
                        KEYS[3],
                        '1',
                        'PX',
                        markerTtl,
                        'NX'
                    )

                    if markerResult then
                        firstNotification = 1
                    end
                end

                if secondExceeded then
                    local markerTtl = secondTtl

                    if markerTtl < 1 then
                        markerTtl = ttlMs
                    end

                    local markerResult = redis.call(
                        'SET',
                        KEYS[4],
                        '1',
                        'PX',
                        markerTtl,
                        'NX'
                    )

                    if markerResult then
                        secondNotification = 1
                    end
                end

                local decision = 'BOTH_EXCEEDED'

                if firstExceeded and not secondExceeded then
                    decision = 'FIRST_EXCEEDED'
                elseif secondExceeded and not firstExceeded then
                    decision = 'SECOND_EXCEEDED'
                end

                local returnedFirstTtl = firstTtl

                if returnedFirstTtl < 1 then
                    returnedFirstTtl = ttlMs
                end

                local returnedSecondTtl = secondTtl

                if returnedSecondTtl < 1 then
                    returnedSecondTtl = ttlMs
                end

                return decision
                    .. ':' .. tostring(firstCurrent)
                    .. ':' .. tostring(secondCurrent)
                    .. ':' .. tostring(returnedFirstTtl)
                    .. ':' .. tostring(returnedSecondTtl)
                    .. ':' .. tostring(firstNotification)
                    .. ':' .. tostring(secondNotification)
            end

            local newFirst =
                redis.call(
                    'INCR',
                    KEYS[1]
                )

            local newSecond =
                redis.call(
                    'INCR',
                    KEYS[2]
                )

            firstTtl =
                redis.call(
                    'PTTL',
                    KEYS[1]
                )

            secondTtl =
                redis.call(
                    'PTTL',
                    KEYS[2]
                )

            if firstTtl < 0 then
                redis.call(
                    'PEXPIRE',
                    KEYS[1],
                    ttlMs
                )

                firstTtl = ttlMs
            end

            if secondTtl < 0 then
                redis.call(
                    'PEXPIRE',
                    KEYS[2],
                    ttlMs
                )

                secondTtl = ttlMs
            end

            return 'ALLOWED'
                .. ':' .. tostring(newFirst)
                .. ':' .. tostring(newSecond)
                .. ':' .. tostring(firstTtl)
                .. ':' .. tostring(secondTtl)
                .. ':0:0'
            """);

    private static final DefaultRedisScript<String>
            INCREMENT_BOTH_AND_CHECK_SCRIPT = script("""
            local ttlMs = tonumber(ARGV[1])
            local firstLimit = tonumber(ARGV[2])
            local secondLimit = tonumber(ARGV[3])

            local newFirst =
                redis.call(
                    'INCR',
                    KEYS[1]
                )

            local newSecond =
                redis.call(
                    'INCR',
                    KEYS[2]
                )

            local firstTtl =
                redis.call(
                    'PTTL',
                    KEYS[1]
                )

            local secondTtl =
                redis.call(
                    'PTTL',
                    KEYS[2]
                )

            if firstTtl < 0 then
                redis.call(
                    'PEXPIRE',
                    KEYS[1],
                    ttlMs
                )

                firstTtl = ttlMs
            end

            if secondTtl < 0 then
                redis.call(
                    'PEXPIRE',
                    KEYS[2],
                    ttlMs
                )

                secondTtl = ttlMs
            end

            local firstExceeded =
                newFirst > firstLimit

            local secondExceeded =
                newSecond > secondLimit

            local firstNotification = 0
            local secondNotification = 0

            if firstExceeded then
                local markerTtl = firstTtl

                if markerTtl < 1 then
                    markerTtl = ttlMs
                end

                local markerResult = redis.call(
                    'SET',
                    KEYS[3],
                    '1',
                    'PX',
                    markerTtl,
                    'NX'
                )

                if markerResult then
                    firstNotification = 1
                end
            end

            if secondExceeded then
                local markerTtl = secondTtl

                if markerTtl < 1 then
                    markerTtl = ttlMs
                end

                local markerResult = redis.call(
                    'SET',
                    KEYS[4],
                    '1',
                    'PX',
                    markerTtl,
                    'NX'
                )

                if markerResult then
                    secondNotification = 1
                end
            end

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
                .. ':' .. tostring(firstNotification)
                .. ':' .. tostring(secondNotification)
            """);

    private static final DefaultRedisScript<Long>
            LOGIN_SUCCESS_CLEANUP_SCRIPT =
            createLoginSuccessCleanupScript();

    private final StringRedisTemplate redisTemplate;

    /**
     * Атомарно увеличивает один fixed-window counter.
     *
     * <p>TTL назначается только при создании окна.
     * Повторные increments не продлевают текущее окно.</p>
     */
    public RateLimitResult incrementAndGet(
            String key,
            Duration ttl
    ) {
        String validatedKey =
                requireKey(key);

        long ttlMillis =
                requireTtlMillis(ttl);

        String result =
                redisTemplate.execute(
                        INCREMENT_ONE_SCRIPT,
                        List.of(validatedKey),
                        Long.toString(ttlMillis)
                );

        if (result == null
                || result.isBlank()) {

            throw new IllegalStateException(
                    "Redis single Lua script returned invalid result"
            );
        }

        String[] parts =
                result.split(
                        ":",
                        -1
                );

        if (parts.length != 2) {
            throw new IllegalStateException(
                    "Redis single Lua script returned invalid result: "
                            + result
            );
        }

        long count =
                parseNonNegativeLong(
                        parts[0],
                        result
                );

        long ttlSeconds =
                toSeconds(
                        parsePositiveTtlMillis(
                                parts[1],
                                result
                        )
                );

        return new RateLimitResult(
                count,
                ttlSeconds
        );
    }

    public DualRateLimitResult tryIncrementBoth(
            String firstKey,
            String firstMarkerKey,
            int firstLimit,
            String secondKey,
            String secondMarkerKey,
            int secondLimit,
            Duration ttl
    ) {
        return executeDualScript(
                TRY_INCREMENT_BOTH_SCRIPT,
                firstKey,
                firstMarkerKey,
                firstLimit,
                secondKey,
                secondMarkerKey,
                secondLimit,
                ttl
        );
    }

    public DualRateLimitResult incrementBothAndCheck(
            String firstKey,
            String firstMarkerKey,
            int firstLimit,
            String secondKey,
            String secondMarkerKey,
            int secondLimit,
            Duration ttl
    ) {
        return executeDualScript(
                INCREMENT_BOTH_AND_CHECK_SCRIPT,
                firstKey,
                firstMarkerKey,
                firstLimit,
                secondKey,
                secondMarkerKey,
                secondLimit,
                ttl
        );
    }

    /**
     * После успешного login:
     *
     * <ol>
     *     <li>удаляет email counter и его marker;</li>
     *     <li>уменьшает IP counter ровно на одну
     *     зарезервированную попытку;</li>
     *     <li>не удаляет ранее накопленные IP failures.</li>
     * </ol>
     */
    public void resetFirstAndDecrementSecond(
            String firstKey,
            String secondKey,
            String firstMarkerKey,
            String secondMarkerKey
    ) {
        validateDistinctKeys(
                firstKey,
                secondKey,
                firstMarkerKey,
                secondMarkerKey
        );

        Long remainingSecondCount =
                redisTemplate.execute(
                        LOGIN_SUCCESS_CLEANUP_SCRIPT,
                        List.of(
                                firstKey,
                                secondKey,
                                firstMarkerKey,
                                secondMarkerKey
                        )
                );

        if (remainingSecondCount == null
                || remainingSecondCount < 0L) {

            throw new IllegalStateException(
                    "Redis login success cleanup returned "
                            + "invalid result"
            );
        }
    }

    /**
     * Marker используется для подавления повторных событий,
     * а не как подтверждение сохранения audit event.
     *
     * <p>При синхронной ошибке публикации созданный marker
     * удаляется, чтобы следующий rejected request получил
     * возможность повторить доставку события.</p>
     */
    public void releaseNotificationMarkers(
            DualRateLimitResult result,
            String firstMarkerKey,
            String secondMarkerKey
    ) {
        Objects.requireNonNull(
                result,
                "result не должен быть null"
        );

        List<String> markerKeys =
                new ArrayList<>(2);

        if (result.firstExceededNotification()) {
            markerKeys.add(
                    requireKey(
                            firstMarkerKey
                    )
            );
        }

        if (result.secondExceededNotification()) {
            markerKeys.add(
                    requireKey(
                            secondMarkerKey
                    )
            );
        }

        if (!markerKeys.isEmpty()) {
            redisTemplate.delete(
                    markerKeys
            );
        }
    }

    private DualRateLimitResult executeDualScript(
            DefaultRedisScript<String> script,
            String firstKey,
            String firstMarkerKey,
            int firstLimit,
            String secondKey,
            String secondMarkerKey,
            int secondLimit,
            Duration ttl
    ) {
        Objects.requireNonNull(
                script,
                "script не должен быть null"
        );

        validateDistinctKeys(
                firstKey,
                secondKey,
                firstMarkerKey,
                secondMarkerKey
        );

        validateLimit(
                firstLimit,
                "firstLimit"
        );

        validateLimit(
                secondLimit,
                "secondLimit"
        );

        long ttlMillis =
                requireTtlMillis(ttl);

        String result =
                redisTemplate.execute(
                        script,
                        List.of(
                                firstKey,
                                secondKey,
                                firstMarkerKey,
                                secondMarkerKey
                        ),
                        Long.toString(
                                ttlMillis
                        ),
                        Integer.toString(
                                firstLimit
                        ),
                        Integer.toString(
                                secondLimit
                        )
                );

        if (result == null
                || result.isBlank()) {

            throw new IllegalStateException(
                    "Redis dual Lua script returned invalid result"
            );
        }

        String[] parts =
                result.split(
                        ":",
                        -1
                );

        if (parts.length != 7) {
            throw new IllegalStateException(
                    "Redis dual Lua script returned invalid result: "
                            + result
            );
        }

        RateLimitDecision decision =
                parseDecision(
                        parts[0],
                        result
                );

        return new DualRateLimitResult(
                decision,
                parseNonNegativeLong(
                        parts[1],
                        result
                ),
                parseNonNegativeLong(
                        parts[2],
                        result
                ),
                toSeconds(
                        parsePositiveTtlMillis(
                                parts[3],
                                result
                        )
                ),
                toSeconds(
                        parsePositiveTtlMillis(
                                parts[4],
                                result
                        )
                ),
                parseNotificationFlag(
                        parts[5],
                        result
                ),
                parseNotificationFlag(
                        parts[6],
                        result
                )
        );
    }

    private static DefaultRedisScript<String> script(
            String text
    ) {
        DefaultRedisScript<String> script =
                new DefaultRedisScript<>();

        script.setResultType(
                String.class
        );

        script.setScriptText(
                text
        );

        return script;
    }

    private static DefaultRedisScript<Long>
    createLoginSuccessCleanupScript() {
        DefaultRedisScript<Long> script =
                new DefaultRedisScript<>();

        script.setResultType(
                Long.class
        );

        script.setScriptText("""
                redis.call(
                    'DEL',
                    KEYS[1],
                    KEYS[3]
                )

                local secondCurrent =
                    tonumber(
                        redis.call(
                            'GET',
                            KEYS[2]
                        ) or '0'
                    )

                if secondCurrent <= 1 then
                    redis.call(
                        'DEL',
                        KEYS[2],
                        KEYS[4]
                    )

                    return 0
                end

                return redis.call(
                    'DECR',
                    KEYS[2]
                )
                """);

        return script;
    }

    private RateLimitDecision parseDecision(
            String value,
            String rawResult
    ) {
        try {
            return RateLimitDecision.valueOf(
                    value
            );
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

            default ->
                    throw new IllegalStateException(
                            "Redis dual Lua script returned invalid "
                                    + "notification flag: "
                                    + rawResult
                    );
        };
    }

    private void validateDistinctKeys(
            String... keys
    ) {
        Set<String> uniqueKeys =
                new HashSet<>(
                        keys.length
                );

        for (String key : keys) {
            uniqueKeys.add(
                    requireKey(
                            key
                    )
            );
        }

        if (uniqueKeys.size()
                != keys.length) {

            throw new IllegalArgumentException(
                    "Redis script keys должны быть различными"
            );
        }
    }

    private String requireKey(
            String key
    ) {
        if (key == null
                || key.isBlank()) {

            throw new IllegalArgumentException(
                    "Redis key не должен быть пустым"
            );
        }

        return key;
    }

    private void validateLimit(
            int limit,
            String name
    ) {
        if (limit < 1) {
            throw new IllegalArgumentException(
                    name
                            + " должен быть положительным"
            );
        }
    }

    private long requireTtlMillis(
            Duration ttl
    ) {
        Objects.requireNonNull(
                ttl,
                "ttl не должен быть null"
        );

        long ttlMillis;

        try {
            ttlMillis =
                    ttl.toMillis();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "ttl слишком большой",
                    exception
            );
        }

        if (ttlMillis < 1L) {
            throw new IllegalArgumentException(
                    "ttl должен быть не меньше 1 ms"
            );
        }

        return ttlMillis;
    }

    private long parseNonNegativeLong(
            String value,
            String rawResult
    ) {
        try {
            long parsed =
                    Long.parseLong(
                            value
                    );

            if (parsed < 0L) {
                throw new IllegalStateException(
                        "Redis returned negative counter: "
                                + rawResult
                );
            }

            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(
                    "Redis returned non-numeric counter: "
                            + rawResult,
                    exception
            );
        }
    }

    private long parsePositiveTtlMillis(
            String value,
            String rawResult
    ) {
        try {
            long parsed =
                    Long.parseLong(
                            value
                    );

            if (parsed < 1L) {
                throw new IllegalStateException(
                        "Redis returned non-positive TTL: "
                                + rawResult
                );
            }

            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(
                    "Redis returned invalid TTL: "
                            + rawResult,
                    exception
            );
        }
    }

    private long toSeconds(
            long ttlMillis
    ) {
        long wholeSeconds =
                ttlMillis / 1_000L;

        return ttlMillis % 1_000L == 0L
                ? Math.max(
                        1L,
                        wholeSeconds
                )
                : Math.max(
                        1L,
                        wholeSeconds + 1L
                );
    }
}