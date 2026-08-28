package ru.safeai.gateway.model.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.safeai.gateway.model.dto.RuntimeModelStatusResponse;
import ru.safeai.gateway.model.service.RuntimeModelStatusService;

/** Administrative read model; credentials and provider URLs are never exposed. */
@RestController
@RequestMapping("/api/admin/models")
@RequiredArgsConstructor
public class ModelRuntimeController {

    private final RuntimeModelStatusService statusService;

    @GetMapping("/runtime")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public RuntimeModelStatusResponse runtime() {
        return statusService.current();
    }
}
