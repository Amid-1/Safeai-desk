package ru.safeai.gateway.ai.provider;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.ai.dto.AiChatResponse;
import ru.safeai.gateway.ai.exception.AiProviderErrorType;
import ru.safeai.gateway.ai.exception.AiProviderException;

import java.util.HashMap;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Bounded labels only. Adapter outcomes are not durable financial evidence. */
@Component
public final class AiProviderMetrics {
    private static final List<String> PROVIDERS = List.of("openai", "anthropic", "mock", "other");
    private final Map<String, Counter> results = new HashMap<>();
    private final Map<String, Counter> retries = new HashMap<>();
    private final Map<String, Counter> gates = new HashMap<>();
    private final Map<String, Counter> recording = new HashMap<>();
    private final Map<String, Timer> durations = new HashMap<>();

    public AiProviderMetrics(MeterRegistry registry) {
        for (String provider : PROVIDERS) {
            for (AiProviderErrorType error : AiProviderErrorType.values()) {
                register(registry, provider, error.name().toLowerCase(Locale.ROOT));
            }
            register(registry, provider, "success");
            register(registry, provider, "unexpected");
            retries.put(provider, registry.counter("safeai.ai.provider.retries", "provider", provider));
            gates.put(provider, registry.counter("safeai.ai.provider.gate.failures", "provider", provider));
            recording.put(provider, registry.counter("safeai.ai.provider.recording.failures", "provider", provider));
            durations.put(provider, Timer.builder("safeai.ai.provider.duration")
                    .tag("provider", provider)
                    .maximumExpectedValue(Duration.ofMinutes(5))
                    .publishPercentileHistogram().register(registry));
        }
    }

    private void register(MeterRegistry registry, String provider, String result) {
        results.put(provider + ":" + result, registry.counter("safeai.ai.provider.attempts",
                "provider", provider, "result", result));
    }

    private static String label(String provider) {
        return PROVIDERS.contains(provider) ? provider : "other";
    }

    AiChatResponse observe(String provider, int attempt, Supplier<AiChatResponse> action) {
        String key = label(provider);
        if (attempt > 1) safely(retries.get(key)::increment);
        long start = System.nanoTime();
        String result = "unexpected";
        try {
            AiChatResponse response = action.get();
            result = "success";
            return response;
        } catch (AiProviderException exception) {
            AiProviderErrorType error = exception.getErrorType();
            if (error != null) result = error.name().toLowerCase(Locale.ROOT);
            throw exception;
        } finally {
            Counter counter = results.get(key + ":" + result);
            safely(counter::increment);
            safely(() -> durations.get(key).record(System.nanoTime() - start, TimeUnit.NANOSECONDS));
        }
    }

    void gateFailure(String provider) {
        safely(gates.get(label(provider))::increment);
    }

    void recordingFailure(String provider) {
        safely(recording.get(label(provider))::increment);
    }

    private static void safely(Runnable observation) {
        try {
            observation.run();
        } catch (RuntimeException ignored) {
            // Telemetry must never change a provider outcome or authorize a retry.
        }
    }
}
