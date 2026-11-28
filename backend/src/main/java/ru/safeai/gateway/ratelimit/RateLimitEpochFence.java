package ru.safeai.gateway.ratelimit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * A durable, operator-owned fence. Never initialize, renew, or repair it from
 * an application request. The check runs inside the same Lua transaction as
 * each mutation, including both phases of refresh admission.
 */
final class RateLimitEpochFence {
    static final String KEY = "safeai:rate-limit:guard:{login}:epoch";
    private static final long MAX_WINDOW_MS = Duration.ofHours(24).toMillis();
    private static final String SHEBANG = "#!lua\n";
    private static final String CHECK = """
            local epochKey = KEYS[#KEYS]
            local fingerprint = ARGV[#ARGV]
            -- Shadow Lua's globals, leaving the original Redis key/argument
            -- arrays intact and preserving the legacy script's exact arity.
            local KEYS = {unpack(KEYS, 1, #KEYS - 1)}
            local ARGV = {unpack(ARGV, 1, #ARGV - 1)}
            local state = redis.call('GET', epochKey)
            if not state or redis.call('PTTL', epochKey) ~= -1 then
                return redis.error_reply('RATE_LIMIT_EPOCH_UNAVAILABLE')
            end
            local server = redis.call('INFO', 'server')
            local replication = redis.call('INFO', 'replication')
            local run = string.match(server, '\\nrun_id:([0-9a-f]+)\\r?\\n')
            local repl = string.match(replication, '\\nmaster_replid:([0-9a-f]+)\\r?\\n')
            local role = string.match(replication, '\\nrole:([^\\r\\n]+)')
            if not run or #run ~= 40 or not repl or #repl ~= 40 or role ~= 'master'
                or state ~= 'ACTIVE:' .. fingerprint .. ':' .. run .. ':' .. repl then
                return redis.error_reply('RATE_LIMIT_EPOCH_UNAVAILABLE')
            end
            """;
    private static final ConcurrentMap<DefaultRedisScript<?>, DefaultRedisScript<?>> GUARDED =
            new ConcurrentHashMap<>();

    private final String fingerprint;

    private RateLimitEpochFence(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    static RateLimitEpochFence disabled() {
        return new RateLimitEpochFence("");
    }

    static RateLimitEpochFence required(String fingerprint) {
        if (!Objects.requireNonNull(fingerprint).matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid RateLimit epoch fingerprint");
        }
        return new RateLimitEpochFence(fingerprint);
    }

    void requireSupportedWindow(long millis) {
        if (!fingerprint.isEmpty() && millis > MAX_WINDOW_MS) {
            throw new IllegalArgumentException("RateLimit window exceeds epoch drain bound");
        }
    }

    <T> T execute(StringRedisTemplate redis, DefaultRedisScript<T> script, List<String> keys, Object... args) {
        if (fingerprint.isEmpty()) {
            return redis.execute(script, keys, args);
        }
        if (keys.contains(KEY)) {
            throw new IllegalArgumentException("RateLimit counter collides with epoch fence");
        }
        var guardedKeys = new ArrayList<>(keys);
        guardedKeys.add(KEY);
        var guardedArgs = Arrays.copyOf(args, args.length + 1);
        guardedArgs[args.length] = fingerprint;
        return redis.execute(guarded(script), guardedKeys, guardedArgs);
    }

    @SuppressWarnings("unchecked") // result type is preserved for each identity-keyed original script
    private static <T> DefaultRedisScript<T> guarded(DefaultRedisScript<T> original) {
        return (DefaultRedisScript<T>) GUARDED.computeIfAbsent(original, source -> {
            String body = source.getScriptAsString();
            if (body.startsWith(SHEBANG)) {
                body = body.substring(SHEBANG.length());
            }
            return new DefaultRedisScript<>(SHEBANG + CHECK + body, source.getResultType());
        });
    }
}
