package ru.safeai.gateway.ratelimit;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.common.security.ProductionEnvironmentCondition;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/** Passive Redis observation. Scrapes never issue Redis commands. */
@Component
final class RateLimitRedisMonitor {
    private static final System.Logger LOGGER = System.getLogger(RateLimitRedisMonitor.class.getName());
    private static final String PREFIX = "safeai.rate.limit.redis.";
    private static final List<String> KEYS = List.of(RateLimitEpochFence.KEY,
            "safeai:rate-limit:guard:{login}:allocations", RedisFixedWindowRateLimiter.REFRESH_ALLOCATION_KEY);
    private static final Set<String> STATES = Set.of("active", "disabled", "missing", "invalid",
            "paused", "mismatch", "primary_changed", "unavailable");
    private static final DefaultRedisScript<String> SCRIPT = observationScript();

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final String fingerprint;
    private final boolean required;
    private volatile Snapshot snapshot = Snapshot.unavailable(0);
    private boolean failureLogged;

    RateLimitRedisMonitor(StringRedisTemplate redis, RateLimitKeyFactory keys, Environment environment,
                          MeterRegistry registry, Clock clock) {
        this.redis = Objects.requireNonNull(redis);
        this.clock = Objects.requireNonNull(clock);
        required = ProductionEnvironmentCondition.isProduction(environment)
                || environment.getProperty("safeai.rate-limit.redis.epoch-fence-enabled", Boolean.class, false);
        fingerprint = keys.epochFingerprint();
        gauge(registry, "observation.healthy", s -> s.healthy() ? 1 : 0);
        gauge(registry, "last.observation.timestamp", Snapshot::timestamp);
        gauge(registry, "memory.accounted.bytes", Snapshot::accounted);
        gauge(registry, "memory.max.bytes", Snapshot::maximum);
        gauge(registry, "memory.rss.bytes", Snapshot::rss);
        gauge(registry, "noeviction", Snapshot::noeviction);
        gauge(registry, "evicted.keys", Snapshot::evicted);
        gauge(registry, "aof.healthy", Snapshot::aofHealthy);
        gauge(registry, "primary", Snapshot::primary);
        Gauge.builder(PREFIX + "epoch.required", this, m -> m.required ? 1 : 0).register(registry);
        for (String state : STATES) {
            Gauge.builder(PREFIX + "epoch.state", this, m -> state.equals(m.snapshot.state()) ? 1 : 0)
                    .tag("state", state).register(registry);
        }
        allocation(registry, "login", Snapshot::login, RedisFixedWindowRateLimiter.LOGIN_ALLOCATION_LIMIT);
        allocation(registry, "refresh", Snapshot::refresh, RedisFixedWindowRateLimiter.REFRESH_ALLOCATION_LIMIT);
    }

    private void gauge(MeterRegistry registry, String name, ToDoubleFunction<Snapshot> value) {
        Gauge.builder(PREFIX + name, this, m -> value.applyAsDouble(m.snapshot)).register(registry);
    }

    private void allocation(MeterRegistry registry, String type, ToDoubleFunction<Snapshot> value, int limit) {
        Gauge.builder(PREFIX + "allocation.active", this, m -> value.applyAsDouble(m.snapshot))
                .tag("type", type).register(registry);
        Gauge.builder(PREFIX + "allocation.limit", this, m -> limit).tag("type", type).register(registry);
    }

    private static DefaultRedisScript<String> observationScript() {
        var script = new DefaultRedisScript<String>();
        script.setLocation(new ClassPathResource("redis/rate-limit-observation.lua"));
        script.setResultType(String.class);
        return script;
    }

    @Scheduled(initialDelay = 10_000, fixedDelay = 30_000)
    synchronized void observe() {
        Snapshot previous = snapshot;
        try {
            String result = redis.execute(SCRIPT, KEYS, fingerprint, required ? "1" : "0");
            snapshot = parse(result, clock.instant().getEpochSecond());
            failureLogged = false;
        } catch (RuntimeException failure) {
            snapshot = Snapshot.unavailable(previous.timestamp());
            if (!failureLogged) {
                LOGGER.log(System.Logger.Level.WARNING, "RateLimit Redis observation unavailable: {0}",
                        failure.getClass().getSimpleName());
                failureLogged = true;
            }
        }
    }

    private Snapshot parse(String result, long timestamp) {
        String[] p = Objects.requireNonNull(result).split(":", -1);
        if (p.length != 11 || !STATES.contains(p[10]) || "unavailable".equals(p[10])
                || required == "disabled".equals(p[10])) {
            throw new IllegalStateException("Invalid Redis observation");
        }
        long accounted = Math.max(0, nonNegative(p[0]) - nonNegative(p[1]));
        return new Snapshot(true, timestamp, accounted, nonNegative(p[2]), nonNegative(p[3]),
                bit(p[4]), nonNegative(p[5]), bit(p[6]), bit(p[7]), nonNegative(p[8]), nonNegative(p[9]), p[10]);
    }

    private static long nonNegative(String value) {
        long parsed = Long.parseLong(value);
        if (parsed < 0) throw new IllegalStateException("Invalid Redis observation number");
        return parsed;
    }

    private static int bit(String value) {
        if ("0".equals(value)) return 0;
        if ("1".equals(value)) return 1;
        throw new IllegalStateException("Invalid Redis observation flag");
    }

    private record Snapshot(boolean healthy, long timestamp, double accounted, double maximum, double rss,
                            double noeviction, double evicted, double aofHealthy, double primary,
                            double login, double refresh, String state) {
        static Snapshot unavailable(long timestamp) {
            return new Snapshot(false, timestamp, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, "unavailable");
        }
    }
}
