package ru.safeai.gateway.model.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import ru.safeai.gateway.model.domain.RuntimeModelProbeResult;
import ru.safeai.gateway.model.domain.RuntimeModelProbeStatus;
import ru.safeai.gateway.model.dto.RuntimeModelProbeResponse;
import ru.safeai.gateway.model.dto.RuntimeModelStatusResponse;
import ru.safeai.gateway.model.dto.RuntimeModelStatusWireResponse;
import ru.safeai.gateway.model.service.RuntimeModelProbeService;
import ru.safeai.gateway.model.service.RuntimeModelStatusService;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelRuntimeControllerTest {

    @Test
    void runtimeReturnsSanitizedExactMoneyWireModel() {
        RuntimeModelStatusService statusService =
                mock(RuntimeModelStatusService.class);

        RuntimeModelProbeService probeService =
                mock(RuntimeModelProbeService.class);

        when(statusService.current())
                .thenReturn(
                        new RuntimeModelStatusResponse(
                                "openai",
                                "gpt-safeai",
                                true,
                                "SINGLE_PROVIDER_STATIC",
                                32_000,
                                4_096,
                                false,
                                false,
                                false,
                                "NOT_DECLARED",
                                "NOT_PROBED",
                                "CONFIGURED",
                                new BigDecimal("2.000000000000"),
                                new BigDecimal("8.000000000000"),
                                "openai-test"
                        )
                );

        ModelRuntimeController controller =
                new ModelRuntimeController(
                        statusService,
                        probeService
                );

        RuntimeModelStatusWireResponse response = controller.runtime();

        assertThat(response.provider()).isEqualTo("openai");
        assertThat(response.model()).isEqualTo("gpt-safeai");
        assertThat(response.inputUsdPer1mTokens())
                .isEqualTo("2.000000000000");
        assertThat(response.outputUsdPer1mTokens())
                .isEqualTo("8.000000000000");

        verify(statusService).current();
    }

    @Test
    void probeReturnsOnlySanitizedProbeEvidence() {
        RuntimeModelStatusService statusService =
                mock(RuntimeModelStatusService.class);

        RuntimeModelProbeService probeService =
                mock(RuntimeModelProbeService.class);

        RuntimeModelProbeResult result =
                new RuntimeModelProbeResult(
                        "openai",
                        "gpt-safeai",
                        RuntimeModelProbeStatus.AVAILABLE,
                        Instant.parse("2026-09-05T18:00:00Z"),
                        384L,
                        200,
                        "Провайдер подтвердил доступность модели"
                );

        when(probeService.probe()).thenReturn(result);

        ModelRuntimeController controller =
                new ModelRuntimeController(
                        statusService,
                        probeService
                );

        RuntimeModelProbeResponse response = controller.probe();

        assertThat(response.status())
                .isEqualTo(RuntimeModelProbeStatus.AVAILABLE);
        assertThat(response.latencyMs()).isEqualTo(384L);
        assertThat(response.httpStatus()).isEqualTo(200);

        verify(probeService).probe();
    }

    @Test
    void runtimeAndProbeHaveDifferentAuthorizationBoundaries()
            throws Exception {
        Method runtime = ModelRuntimeController.class.getMethod("runtime");
        Method probe = ModelRuntimeController.class.getMethod("probe");

        assertThat(runtime.getAnnotation(GetMapping.class).value())
                .containsExactly("/runtime");
        assertThat(runtime.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasAnyRole('ADMIN', 'SUPER_ADMIN')");

        assertThat(probe.getAnnotation(PostMapping.class).value())
                .containsExactly("/runtime/probe");
        assertThat(probe.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasRole('SUPER_ADMIN')");
    }
}
