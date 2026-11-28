// SafeAI-Desk/backend/src/main/java/ru/safeai/gateway/erasure/ErasureQueueController.java
package ru.safeai.gateway.erasure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.erasure.ErasureCaseTypes.CasePage;

/** R1a admin queue is read-only; no approvals or execution exposed. */
@RestController
@RequestMapping("/api/admin/erasure")
@PreAuthorize("hasRole('ADMIN')")
@ConditionalOnProperty(name = "safeai.erasure.requests.enabled", havingValue = "true")
public class ErasureQueueController {
    private final ErasureCaseService service;

    public ErasureQueueController(ErasureCaseService service) {
        this.service = service;
    }

    @GetMapping("/queue")
    public CasePage queue(@AuthenticationPrincipal(errorOnInvalidType = true)
            SafeAiUserPrincipal actor) {
        return service.tenantQueue(actor);
    }
}
