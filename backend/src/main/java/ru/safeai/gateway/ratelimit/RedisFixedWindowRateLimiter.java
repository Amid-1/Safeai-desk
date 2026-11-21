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
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class RedisFixedWindowRateLimiter {

    private static final DefaultRedisScript<String>
            INCREMENT_ONE_SCRIPT = script("""
            local ttlMs = tonumber(ARGV[1])
            local function counter(key)
                local raw = redis.call('GET', key)
                if not raw then return 0 end
                if not string.match(raw, '^%d+$') then
                    return redis.error_reply('INVALID_RATE_LIMIT_COUNTER')
                end
                local normalized = string.gsub(raw, '^0+', '')
                if normalized == '' then normalized = '0' end
                if raw ~= normalized then return redis.error_reply('INVALID_RATE_LIMIT_COUNTER') end
                -- Stay strictly below signed int64 max: an INCR must be safe.
                local max = '9223372036854775806'
                if #normalized > #max or (#normalized == #max and normalized > max) then
                    return redis.error_reply('INVALID_RATE_LIMIT_COUNTER')
                end
                return tonumber(normalized)
            end
            local currentValue = counter(KEYS[1])
            if type(currentValue) == 'table' then return currentValue end

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

            local function counter(key)
                local raw = redis.call('GET', key)
                if not raw then return 0 end
                if not string.match(raw, '^%d+$') then
                    return redis.error_reply('INVALID_RATE_LIMIT_COUNTER')
                end
                local normalized = string.gsub(raw, '^0+', '')
                if normalized == '' then normalized = '0' end
                if raw ~= normalized then return redis.error_reply('INVALID_RATE_LIMIT_COUNTER') end
                -- Stay strictly below signed int64 max: an INCR must be safe.
                local max = '9223372036854775806'
                if #normalized > #max or (#normalized == #max and normalized > max) then
                    return redis.error_reply('INVALID_RATE_LIMIT_COUNTER')
                end
                return tonumber(normalized)
            end
            local firstCurrent = counter(KEYS[1])
            if type(firstCurrent) == 'table' then return firstCurrent end
            local secondCurrent = counter(KEYS[2])
            if type(secondCurrent) == 'table' then return secondCurrent end

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
                    local markerTtl = math.max(1, firstTtl)

                    local markerResult = redis.call(
                        'SET',
                        KEYS[3],
                        ARGV[4],
                        'PX',
                        markerTtl,
                        'NX'
                    )

                    if markerResult then
                        firstNotification = 1
                    end
                end

                if secondExceeded then
                    local markerTtl = math.max(1, secondTtl)

                    local markerResult = redis.call(
                        'SET',
                        KEYS[4],
                        ARGV[4],
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

                local returnedFirstTtl = firstTtl < 0 and ttlMs or math.max(1, firstTtl)

                local returnedSecondTtl = secondTtl < 0 and ttlMs or math.max(1, secondTtl)

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
                .. ':' .. tostring(math.max(1, firstTtl))
                .. ':' .. tostring(math.max(1, secondTtl))
                .. ':0:0'
            """);

    private static final DefaultRedisScript<String>
            INCREMENT_BOTH_AND_CHECK_SCRIPT = script("""
            local ttlMs = tonumber(ARGV[1])
            local firstLimit = tonumber(ARGV[2])
            local secondLimit = tonumber(ARGV[3])

            local function counter(key)
                local raw = redis.call('GET', key)
                if not raw then return 0 end
                if not string.match(raw, '^%d+$') then
                    return redis.error_reply('INVALID_RATE_LIMIT_COUNTER')
                end
                local normalized = string.gsub(raw, '^0+', '')
                if normalized == '' then normalized = '0' end
                if raw ~= normalized then return redis.error_reply('INVALID_RATE_LIMIT_COUNTER') end
                -- Stay strictly below signed int64 max: an INCR must be safe.
                local max = '9223372036854775806'
                if #normalized > #max or (#normalized == #max and normalized > max) then
                    return redis.error_reply('INVALID_RATE_LIMIT_COUNTER')
                end
                return tonumber(normalized)
            end
            local firstCurrent = counter(KEYS[1])
            if type(firstCurrent) == 'table' then return firstCurrent end
            local secondCurrent = counter(KEYS[2])
            if type(secondCurrent) == 'table' then return secondCurrent end

            local firstMissing = redis.call('EXISTS', KEYS[1]) == 0
            local secondMissing = redis.call('EXISTS', KEYS[2]) == 0
            -- A deleted or evicted counter starts a new generation even if its sidecar survived.
            if firstMissing then redis.call('DEL', KEYS[5]) end
            if secondMissing then redis.call('DEL', KEYS[6]) end

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
                local markerTtl = math.max(1, firstTtl)

                local markerResult = redis.call(
                    'SET',
                    KEYS[3],
                    ARGV[4],
                    'PX',
                    markerTtl,
                    'NX'
                )

                if markerResult then
                    firstNotification = 1
                end
            end

            if secondExceeded then
                local markerTtl = math.max(1, secondTtl)

                local markerResult = redis.call(
                    'SET',
                    KEYS[4],
                    ARGV[4],
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

            if decision == 'ALLOWED' then
                local function window(counterKey, windowKey)
                    local generation = redis.call('GET', windowKey)
                    if generation then return generation end
                    redis.call('SET', windowKey, ARGV[4], 'PXAT', redis.call('PEXPIRETIME', counterKey))
                    return ARGV[4]
                end
                local firstWindow = window(KEYS[1], KEYS[5])
                local secondWindow = window(KEYS[2], KEYS[6])
                redis.call('SET', KEYS[7], firstWindow .. ':' .. secondWindow .. ':' .. KEYS[2],
                    'PX', math.max(1, firstTtl, secondTtl))
            end

            return decision
                .. ':' .. tostring(newFirst)
                .. ':' .. tostring(newSecond)
                .. ':' .. tostring(math.max(1, firstTtl))
                .. ':' .. tostring(math.max(1, secondTtl))
                .. ':' .. tostring(firstNotification)
                .. ':' .. tostring(secondNotification)
            """);

    private static final DefaultRedisScript<Long>
            LOGIN_SUCCESS_CLEANUP_SCRIPT = new DefaultRedisScript<>("""
            local receipt = redis.call('GET', KEYS[5])
            if not receipt then return 0 end
            local firstWindow, secondWindow, secondKey = string.match(receipt, '^([^:]+):([^:]+):(.+)$')
            if secondKey ~= KEYS[2] then return redis.error_reply('INVALID_LOGIN_RECEIPT') end

            local function eligible(counterKey, generationKey, generation)
                if redis.call('GET', generationKey) ~= generation then return false end
                local raw = redis.call('GET', counterKey)
                if not raw then return false end
                local max = '9223372036854775807'
                if not string.match(raw, '^%d+$') or (#raw > 1 and string.sub(raw, 1, 1) == '0')
                    or #raw > #max or (#raw == #max and raw > max) then
                    return redis.error_reply('INVALID_RATE_LIMIT_COUNTER')
                end
                return raw ~= '0'
            end

            local first = eligible(KEYS[1], KEYS[3], firstWindow)
            if type(first) == 'table' then return first end
            local second = eligible(KEYS[2], KEYS[4], secondWindow)
            if type(second) == 'table' then return second end
            if first then redis.call('DECR', KEYS[1]) end
            if second then redis.call('DECR', KEYS[2]) end
            redis.call('DEL', KEYS[5])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> RELEASE_MARKERS_SCRIPT = new DefaultRedisScript<>("""
            local removed = 0
            for _, key in ipairs(KEYS) do
                if redis.call('GET', key) == ARGV[1] then
                    removed = removed + redis.call('DEL', key)
                end
            end
            return removed
            """, Long.class);

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

    void compensateLogin(LoginRateLimitService.Attempt attempt) {
        String firstKey = attempt.emailKey();
        String secondKey = attempt.ipKey();
        Long result = redisTemplate.execute(
                LOGIN_SUCCESS_CLEANUP_SCRIPT,
                List.of(firstKey, secondKey, generationKey(firstKey), generationKey(secondKey),
                        receiptKey(firstKey, attempt.id()))
        );
        if (result == null || (result != 0L && result != 1L)) {
            throw new IllegalStateException("Redis login success cleanup returned invalid result");
        }
    }

    static String generationKey(String counterKey) {
        return counterKey + ":generation";
    }

    static String receiptKey(String firstKey, UUID id) {
        return firstKey + ":attempt:" + id;
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

        if (!markerKeys.isEmpty() && result.operationId() != null) {
            redisTemplate.execute(RELEASE_MARKERS_SCRIPT, markerKeys, result.operationId().toString());
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

        UUID operationId = UUID.randomUUID();
        List<String> keys = new ArrayList<>(List.of(firstKey, secondKey, firstMarkerKey, secondMarkerKey));
        if (script == INCREMENT_BOTH_AND_CHECK_SCRIPT) {
            keys.add(generationKey(firstKey));
            keys.add(generationKey(secondKey));
            keys.add(receiptKey(firstKey, operationId));
            validateDistinctKeys(keys.toArray(String[]::new));
        }

        String result = redisTemplate.execute(
                script, keys, Long.toString(ttlMillis), Integer.toString(firstLimit),
                Integer.toString(secondLimit), operationId.toString()
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
                ),
                operationId
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
