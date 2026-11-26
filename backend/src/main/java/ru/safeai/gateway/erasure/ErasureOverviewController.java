package ru.safeai.gateway.erasure;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.erasure.ErasureReviewTypes.ReviewSummary;

/** Platform aggregate only. Independent of the review mutation feature flag. */
@RestController
@RequestMapping("/api/admin/erasure")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class ErasureOverviewController {
    private final ErasureReviewService service;

    public ErasureOverviewController(ErasureReviewService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public ReviewSummary overview(@AuthenticationPrincipal SafeAiUserPrincipal actor) {
        return service.platformSummary(actor);
    }
}
