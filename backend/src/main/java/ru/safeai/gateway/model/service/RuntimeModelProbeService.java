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
            return new RuntimeModelProbeResult(
                    provider,
                    runtime.model(),
                    RuntimeModelProbeStatus.ERROR,
                    Instant.now(clock),
                    0L,
                    null,
                    "Для текущего provider проверка доступности не зарегистрирована"
            );
        }

        RuntimeModelProbeResult result = Objects.requireNonNull(
                probe.probe(),
                "Runtime probe вернул null"
        );

        if (!provider.equals(normalizeProvider(result.provider()))
                || !runtime.model().equals(result.model())) {
            return new RuntimeModelProbeResult(
                    provider,
                    runtime.model(),
                    RuntimeModelProbeStatus.CONFIGURATION_MISMATCH,
                    Instant.now(clock),
                    result.latencyMs(),
                    result.httpStatus(),
                    "Результат проверки не совпал с текущей runtime-конфигурацией"
            );
        }

        return result;
    }

    private static String normalizeProvider(
            String value
    ) {
        return Objects.requireNonNull(
                value,
                "provider не должен быть null"
        )
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}
