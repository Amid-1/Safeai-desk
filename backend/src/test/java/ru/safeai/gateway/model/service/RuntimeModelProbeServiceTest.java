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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeModelProbeServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-10-03T12:00:00Z");

    private static final Clock CLOCK =
            Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void successfulProbeIsReissuedAsSanitizedServerTimestampedEvidence() {
        RuntimeModelStatusService statusService = mock(RuntimeModelStatusService.class);
        RuntimeModelHealthProbe healthProbe = mock(RuntimeModelHealthProbe.class);

        when(statusService.current()).thenReturn(runtime("openai", "gpt-safeai"));
        when(healthProbe.provider()).thenReturn(" OpenAI ");
        when(healthProbe.probe()).thenReturn(new RuntimeModelProbeResult(
                "openai",
                "gpt-safeai",
                RuntimeModelProbeStatus.AVAILABLE,
                NOW.minusSeconds(30),
                120L,
                200,
                "adapter-controlled text that must not escape"
        ));

        RuntimeModelProbeService service = new RuntimeModelProbeService(
                statusService,
                List.of(healthProbe),
                CLOCK
        );

        RuntimeModelProbeResult result = service.probe();

        assertThat(result.provider()).isEqualTo("openai");
        assertThat(result.model()).isEqualTo("gpt-safeai");
        assertThat(result.status()).isEqualTo(RuntimeModelProbeStatus.AVAILABLE);
        assertThat(result.checkedAt()).isEqualTo(NOW);
        assertThat(result.latencyMs()).isEqualTo(120L);
        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.message())
                .isEqualTo("Провайдер подтвердил доступность модели")
                .doesNotContain("adapter-controlled");

        verify(healthProbe).probe();
        verify(statusService).recordProbe(result);
    }

    @Test
    void mismatchingProbeIdentityFailsClosedAndIsRecorded() {
        RuntimeModelStatusService statusService = mock(RuntimeModelStatusService.class);
        RuntimeModelHealthProbe healthProbe = mock(RuntimeModelHealthProbe.class);

        when(statusService.current()).thenReturn(runtime("openai", "gpt-safeai"));
        when(healthProbe.provider()).thenReturn("openai");
        when(healthProbe.probe()).thenReturn(new RuntimeModelProbeResult(
                "openai",
                "different-model",
                RuntimeModelProbeStatus.AVAILABLE,
                NOW.minusSeconds(10),
                50L,
                200,
                "untrusted"
        ));

        RuntimeModelProbeService service = new RuntimeModelProbeService(
                statusService,
                List.of(healthProbe),
                CLOCK
        );

        RuntimeModelProbeResult result = service.probe();

        assertThat(result.status()).isEqualTo(RuntimeModelProbeStatus.CONFIGURATION_MISMATCH);
        assertThat(result.provider()).isEqualTo("openai");
        assertThat(result.model()).isEqualTo("gpt-safeai");
        assertThat(result.checkedAt()).isEqualTo(NOW);
        assertThat(result.message())
                .isEqualTo("Результат проверки не совпал с текущей runtime-конфигурацией");
        verify(statusService).recordProbe(result);
    }

    @Test
    void uncheckedAdapterFailureBecomesSanitizedErrorAndIsRecorded() {
        RuntimeModelStatusService statusService = mock(RuntimeModelStatusService.class);
        RuntimeModelHealthProbe healthProbe = mock(RuntimeModelHealthProbe.class);

        when(statusService.current()).thenReturn(runtime("openai", "gpt-safeai"));
        when(healthProbe.provider()).thenReturn("openai");
        when(healthProbe.probe()).thenThrow(new IllegalStateException(
                "secret-token=must-never-leak"
        ));

        RuntimeModelProbeService service = new RuntimeModelProbeService(
                statusService,
                List.of(healthProbe),
                CLOCK
        );

        RuntimeModelProbeResult result = service.probe();

        assertThat(result.status()).isEqualTo(RuntimeModelProbeStatus.ERROR);
        assertThat(result.checkedAt()).isEqualTo(NOW);
        assertThat(result.httpStatus()).isNull();
        assertThat(result.message())
                .isEqualTo("Не удалось безопасно выполнить проверку runtime-модели")
                .doesNotContain("secret-token");
        verify(statusService).recordProbe(result);
    }

    @Test
    void nullAdapterResultBecomesSanitizedError() {
        RuntimeModelStatusService statusService = mock(RuntimeModelStatusService.class);
        RuntimeModelHealthProbe healthProbe = mock(RuntimeModelHealthProbe.class);

        when(statusService.current()).thenReturn(runtime("openai", "gpt-safeai"));
        when(healthProbe.provider()).thenReturn("openai");
        when(healthProbe.probe()).thenReturn(null);

        RuntimeModelProbeService service = new RuntimeModelProbeService(
                statusService,
                List.of(healthProbe),
                CLOCK
        );

        RuntimeModelProbeResult result = service.probe();

        assertThat(result.status()).isEqualTo(RuntimeModelProbeStatus.ERROR);
        assertThat(result.message())
                .isEqualTo("Не удалось безопасно выполнить проверку runtime-модели");
        verify(statusService).recordProbe(result);
    }

    @Test
    void missingProbeReturnsSanitizedErrorAndDoesNotCallAnyAdapter() {
        RuntimeModelStatusService statusService = mock(RuntimeModelStatusService.class);
        RuntimeModelHealthProbe unrelated = mock(RuntimeModelHealthProbe.class);

        when(statusService.current()).thenReturn(runtime("custom", "custom-model"));
        when(unrelated.provider()).thenReturn("openai");

        RuntimeModelProbeService service = new RuntimeModelProbeService(
                statusService,
                List.of(unrelated),
                CLOCK
        );

        RuntimeModelProbeResult result = service.probe();

        assertThat(result.status()).isEqualTo(RuntimeModelProbeStatus.ERROR);
        assertThat(result.provider()).isEqualTo("custom");
        assertThat(result.model()).isEqualTo("custom-model");
        assertThat(result.checkedAt()).isEqualTo(NOW);
        assertThat(result.httpStatus()).isNull();
        assertThat(result.message())
                .isEqualTo("Для текущего provider проверка доступности не зарегистрирована");
        verify(unrelated, never()).probe();
        verify(statusService).recordProbe(result);
    }

    @Test
    void duplicateNormalizedProviderRegistrationFailsFast() {
        RuntimeModelStatusService statusService = mock(RuntimeModelStatusService.class);
        RuntimeModelHealthProbe first = mock(RuntimeModelHealthProbe.class);
        RuntimeModelHealthProbe second = mock(RuntimeModelHealthProbe.class);

        when(first.provider()).thenReturn("openai");
        when(second.provider()).thenReturn(" OpenAI ");

        assertThatThrownBy(() -> new RuntimeModelProbeService(
                statusService,
                List.of(first, second),
                CLOCK
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("несколько runtime probes");
    }

    @Test
    void blankProviderRegistrationFailsFast() {
        RuntimeModelStatusService statusService = mock(RuntimeModelStatusService.class);
        RuntimeModelHealthProbe probe = mock(RuntimeModelHealthProbe.class);
        when(probe.provider()).thenReturn("   ");

        assertThatThrownBy(() -> new RuntimeModelProbeService(
                statusService,
                List.of(probe),
                CLOCK
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not be blank");
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
