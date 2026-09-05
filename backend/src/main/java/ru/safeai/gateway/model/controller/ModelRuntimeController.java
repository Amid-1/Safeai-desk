package ru.safeai.gateway.model.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.safeai.gateway.model.dto.RuntimeModelProbeResponse;
import ru.safeai.gateway.model.dto.RuntimeModelStatusWireResponse;
import ru.safeai.gateway.model.service.RuntimeModelProbeService;
import ru.safeai.gateway.model.service.RuntimeModelStatusService;

import java.util.Objects;

/**
 * Administrative runtime read model and explicit connectivity probe.
 * Credentials, provider URLs and provider response bodies are never exposed.
 */
@RestController
@RequestMapping("/api/admin/models")
public class ModelRuntimeController {

    private final RuntimeModelStatusService statusService;
    private final RuntimeModelProbeService probeService;

    public ModelRuntimeController(
            RuntimeModelStatusService statusService,
            RuntimeModelProbeService probeService
    ) {
        this.statusService = Objects.requireNonNull(
                statusService,
                "statusService не должен быть null"
        );
        this.probeService = Objects.requireNonNull(
                probeService,
                "probeService не должен быть null"
        );
    }

    @GetMapping("/runtime")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public RuntimeModelStatusWireResponse runtime() {
        return RuntimeModelStatusWireResponse.from(
                statusService.current()
        );
    }

    /**
     * Performs a metadata-only provider/model availability check.
     *
     * <p>SUPER_ADMIN only: even a metadata probe reaches the external provider
     * and can consume provider-side rate limit.</p>
     */
    @PostMapping("/runtime/probe")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public RuntimeModelProbeResponse probe() {
        return RuntimeModelProbeResponse.from(
                probeService.probe()
        );
    }
}
