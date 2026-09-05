package ru.safeai.gateway.model.service;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.model.domain.RuntimeModelProbeResult;
import ru.safeai.gateway.model.domain.RuntimeModelProbeStatus;
import ru.safeai.gateway.model.dto.RuntimeModelStatusResponse;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeModelProbeServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-09-05T18:00:00Z");

    private static final Clock CLOCK =
            Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void delegatesToProbeForPhysicallyConfiguredProvider() {
        RuntimeModelStatusService statusService =
                mock(RuntimeModelStatusService.class);

        RuntimeModelHealthProbe healthProbe =
                mock(RuntimeModelHealthProbe.class);

        when(statusService.current())
                .thenReturn(runtime("openai", "gpt-safeai"));

        when(healthProbe.provider())
                .thenReturn("openai");

        RuntimeModelProbeResult expected =
                new RuntimeModelProbeResult(
                        "openai",
                        "gpt-safeai",
                        RuntimeModelProbeStatus.AVAILABLE,
                        NOW,
                        120L,
                        200,
                        "Провайдер подтвердил доступность модели"
                );

        when(healthProbe.probe()).thenReturn(expected);

        RuntimeModelProbeService service =
                new RuntimeModelProbeService(
                        statusService,
                        List.of(healthProbe),
                        CLOCK
                );

        assertThat(service.probe()).isSameAs(expected);
        verify(healthProbe).probe();
    }

    @Test
    void mismatchingProbeIdentityFailsClosed() {
        RuntimeModelStatusService statusService =
                mock(RuntimeModelStatusService.class);

        RuntimeModelHealthProbe healthProbe =
                mock(RuntimeModelHealthProbe.class);

        when(statusService.current())
                .thenReturn(runtime("openai", "gpt-safeai"));

        when(healthProbe.provider()).thenReturn("openai");

        when(healthProbe.probe())
                .thenReturn(
                        new RuntimeModelProbeResult(
                                "openai",
                                "different-model",
                                RuntimeModelProbeStatus.AVAILABLE,
                                NOW,
                                50L,
                                200,
                                "Провайдер подтвердил доступность модели"
                        )
                );

        RuntimeModelProbeService service =
                new RuntimeModelProbeService(
                        statusService,
                        List.of(healthProbe),
                        CLOCK
                );

        RuntimeModelProbeResult result = service.probe();

        assertThat(result.status())
                .isEqualTo(
                        RuntimeModelProbeStatus.CONFIGURATION_MISMATCH
                );
        assertThat(result.provider()).isEqualTo("openai");
        assertThat(result.model()).isEqualTo("gpt-safeai");
    }

    @Test
    void missingProbeReturnsSanitizedError() {
        RuntimeModelStatusService statusService =
                mock(RuntimeModelStatusService.class);

        when(statusService.current())
                .thenReturn(runtime("custom", "custom-model"));

        RuntimeModelProbeService service =
                new RuntimeModelProbeService(
                        statusService,
                        List.of(),
                        CLOCK
                );

        RuntimeModelProbeResult result = service.probe();

        assertThat(result.status())
                .isEqualTo(RuntimeModelProbeStatus.ERROR);
        assertThat(result.httpStatus()).isNull();
    }

    private static RuntimeModelStatusResponse runtime(
            String provider,
            String model
    ) {
        return new RuntimeModelStatusResponse(
                provider,
                model,
                true,
                "SINGLE_PROVIDER_STATIC",
                64_000,
                2_048,
                false,
                false,
                false,
                "NOT_DECLARED",
                "NOT_PROBED",
                "FREE",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "test-pricing"
        );
    }
}
