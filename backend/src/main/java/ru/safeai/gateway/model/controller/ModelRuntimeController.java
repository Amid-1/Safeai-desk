package ru.safeai.gateway.model.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.safeai.gateway.model.dto.RuntimeModelStatusWireResponse;
import ru.safeai.gateway.model.service.RuntimeModelStatusService;

import java.util.Objects;

/** Administrative read model; credentials and provider URLs are never exposed. */
@RestController
@RequestMapping("/api/admin/models")
public class ModelRuntimeController {

    private final RuntimeModelStatusService statusService;

    public ModelRuntimeController(
            RuntimeModelStatusService statusService
    ) {
        this.statusService = Objects.requireNonNull(
                statusService,
                "statusService не должен быть null"
        );
    }

    @GetMapping("/runtime")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public RuntimeModelStatusWireResponse runtime() {
        return RuntimeModelStatusWireResponse.from(
                statusService.current()
        );
    }
}
