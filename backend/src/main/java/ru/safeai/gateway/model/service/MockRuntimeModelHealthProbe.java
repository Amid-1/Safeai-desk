package ru.safeai.gateway.model.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.model.domain.RuntimeModelProbeResult;
import ru.safeai.gateway.model.domain.RuntimeModelProbeStatus;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Local metadata probe for the mock provider. */
@Component
@ConditionalOnProperty(
        name = "safeai.ai.provider",
        havingValue = "mock",
        matchIfMissing = true
)
public final class MockRuntimeModelHealthProbe
        implements RuntimeModelHealthProbe {

    private static final String PROVIDER = "mock";
    private static final String MODEL = "mock-safeai";

    private final Clock clock;

    public MockRuntimeModelHealthProbe(
            Clock clock
    ) {
        this.clock = Objects.requireNonNull(
                clock,
                "clock не должен быть null"
        );
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public RuntimeModelProbeResult probe() {
        return new RuntimeModelProbeResult(
                PROVIDER,
                MODEL,
                RuntimeModelProbeStatus.AVAILABLE,
                Instant.now(clock),
                0L,
                null,
                "Локальный mock provider доступен"
        );
    }
}
