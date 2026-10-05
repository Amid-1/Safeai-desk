package ru.safeai.gateway.model.service;

import org.springframework.stereotype.Service;
import ru.safeai.gateway.model.domain.RuntimeModelProbeResult;
import ru.safeai.gateway.model.domain.RuntimeModelProbeStatus;
import ru.safeai.gateway.model.dto.RuntimeModelStatusResponse;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Resolves the probe for the physically configured runtime. */
@Service
public class RuntimeModelProbeService {

    private final RuntimeModelStatusService statusService;
    private final Map<String, RuntimeModelHealthProbe> probes;
    private final Clock clock;

    public RuntimeModelProbeService(
            RuntimeModelStatusService statusService,
            List<RuntimeModelHealthProbe> probes,
            Clock clock
    ) {
        this.statusService = Objects.requireNonNull(
                statusService,
                "statusService не должен быть null"
        );
        this.clock = Objects.requireNonNull(
                clock,
                "clock не должен быть null"
        );

        Objects.requireNonNull(
                probes,
                "probes не должен быть null"
        );

        Map<String, RuntimeModelHealthProbe> indexed = new HashMap<>();

        for (RuntimeModelHealthProbe probe : probes) {
            Objects.requireNonNull(
                    probe,
                    "probe не должен быть null"
            );

            String provider = normalizeProvider(probe.provider());

            RuntimeModelHealthProbe previous = indexed.put(
                    provider,
                    probe
            );

            if (previous != null) {
                throw new IllegalStateException(
                        "Для provider="
                                + provider
                                + " зарегистрировано несколько runtime probes"
                );
            }
        }

        this.probes = Map.copyOf(indexed);
    }

    public RuntimeModelProbeResult probe() {
        RuntimeModelStatusResponse runtime = statusService.current();
        String provider = normalizeProvider(runtime.provider());

        RuntimeModelHealthProbe probe = probes.get(provider);

        if (probe == null) {
            RuntimeModelProbeResult missing = new RuntimeModelProbeResult(
                    provider,
                    runtime.model(),
                    RuntimeModelProbeStatus.ERROR,
                    Instant.now(clock),
                    0L,
                    null,
                    "Для текущего provider проверка доступности не зарегистрирована"
            );
            statusService.recordProbe(missing);
            return missing;
        }

        long startedNanos = System.nanoTime();
        RuntimeModelProbeResult result;
        try {
            result = Objects.requireNonNull(probe.probe(), "Runtime probe returned null");
            if (!provider.equals(normalizeProvider(result.provider()))
                    || !runtime.model().equals(result.model())) {
                result = new RuntimeModelProbeResult(
                        provider, runtime.model(), RuntimeModelProbeStatus.CONFIGURATION_MISMATCH,
                        clock.instant(), result.latencyMs(), result.httpStatus(),
                        "Результат проверки не совпал с текущей runtime-конфигурацией");
            } else {
                // Do not expose arbitrary adapter exception/response text to the caller.
                result = new RuntimeModelProbeResult(
                        provider, runtime.model(), result.status(), clock.instant(),
                        result.latencyMs(), result.httpStatus(), publicMessage(result.status()));
            }
        } catch (RuntimeException exception) {
            result = new RuntimeModelProbeResult(
                    provider, runtime.model(), RuntimeModelProbeStatus.ERROR,
                    clock.instant(), Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L),
                    null, "Не удалось безопасно выполнить проверку runtime-модели");
        }
        statusService.recordProbe(result);
        return result;
    }

    private static String publicMessage(RuntimeModelProbeStatus status) {
        return switch (status) {
            case AVAILABLE -> "Провайдер подтвердил доступность модели";
            case AUTH_ERROR -> "Провайдер отклонил аутентификацию или запретил доступ к модели";
            case MODEL_NOT_FOUND -> "Провайдер не нашёл настроенную модель";
            case RATE_LIMITED -> "Провайдер временно ограничил запросы";
            case CONFIGURATION_MISMATCH -> "Результат проверки не совпал с runtime-конфигурацией";
            case UNAVAILABLE -> "Провайдер временно недоступен";
            case ERROR -> "Проверка завершилась внутренней ошибкой";
        };
    }

    private static String normalizeProvider(
            String value
    ) {
        String normalized = Objects.requireNonNull(
                value,
                "provider не должен быть null"
        )
                .trim()
                .toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalStateException("Runtime probe provider must not be blank");
        }
        return normalized;
    }
}
