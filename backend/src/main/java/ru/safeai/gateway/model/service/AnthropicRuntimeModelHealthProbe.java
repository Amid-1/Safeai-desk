package ru.safeai.gateway.model.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.safeai.gateway.ai.provider.anthropic.AnthropicProperties;
import ru.safeai.gateway.model.domain.RuntimeModelProbeResult;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;

/**
 * Anthropic metadata-only model probe.
 *
 * <p>No customer payload is sent and the response body is discarded.</p>
 */
@Component
@ConditionalOnProperty(
        name = "safeai.ai.provider",
        havingValue = "anthropic"
)
public final class AnthropicRuntimeModelHealthProbe
        implements RuntimeModelHealthProbe {

    private static final String PROVIDER = "anthropic";

    private final AnthropicProperties properties;
    private final Clock clock;

    public AnthropicRuntimeModelHealthProbe(
            AnthropicProperties properties,
            Clock clock
    ) {
        this.properties = Objects.requireNonNull(
                properties,
                "properties не должен быть null"
        );
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
        return RuntimeModelProbeHttpSupport.probeModel(
                PROVIDER,
                properties.model(),
                properties.baseUrl(),
                Map.of(
                        "x-api-key",
                        properties.apiKey(),
                        "anthropic-version",
                        properties.version()
                ),
                properties.connectTimeout(),
                properties.readTimeout(),
                clock
        );
    }
}
