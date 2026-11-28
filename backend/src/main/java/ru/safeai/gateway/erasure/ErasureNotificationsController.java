package ru.safeai.gateway.erasure;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

@RestController
public class ErasureNotificationsController {
    private final ErasureNotifications reads;

    public ErasureNotificationsController(ErasureNotifications reads) {
        this.reads = reads;
    }

    @GetMapping("/api/erasure/requests/notifications")
    @PreAuthorize("hasAnyRole('USER','ADMIN','SUPER_ADMIN')")
    public ErasureNotifications.Feed notifications(@AuthenticationPrincipal SafeAiUserPrincipal actor) {
        return reads.feed(actor);
    }
}
