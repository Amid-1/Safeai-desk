// SafeAI-Desk/backend/src/main/java/ru/safeai/gateway/retention/RetentionManagementController.java
package ru.safeai.gateway.retention;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

/** Platform policy API; tenant administrators cannot change global admission. */
@RestController
@RequestMapping("/api/admin/retention")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class RetentionManagementController {
    private final RetentionManagementService service;

    public RetentionManagementController(RetentionManagementService service) {
        this.service = service;
    }

    @GetMapping
    public RetentionManagementService.Status status(
            @AuthenticationPrincipal(errorOnInvalidType = true) SafeAiUserPrincipal actor) {
        return service.status(actor);
    }

    @PutMapping
    public RetentionManagementService.Status update(@RequestBody RetentionManagementService.Change change,
            @AuthenticationPrincipal(errorOnInvalidType = true) SafeAiUserPrincipal actor) {
        return service.update(change, actor);
    }
}
