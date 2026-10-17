package ru.safeai.gateway.audit.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.safeai.gateway.audit.service.AuditOutboxRecoveryService;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.util.UUID;

/** Operator endpoint: never expose unredacted outbox details in a response. */
@RestController
@RequestMapping("/api/admin/audit-events/outbox")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AuditOutboxRecoveryController {

    private final AuditOutboxRecoveryService recoveryService;

    @PostMapping("/dead-letters/{eventId}/recover")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recover(
            @PathVariable UUID eventId,
            @AuthenticationPrincipal(errorOnInvalidType = true)
            SafeAiUserPrincipal operator
    ) {
        recoveryService.recover(eventId, operator);
    }
}
