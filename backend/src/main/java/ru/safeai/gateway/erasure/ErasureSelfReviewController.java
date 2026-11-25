package ru.safeai.gateway.erasure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.erasure.ErasureReviewTypes.*;

import java.util.UUID;

/** Requester-only visibility. Deliberately no unverified identity-proof endpoint. */
@RestController
@RequestMapping("/api/erasure/requests")
@PreAuthorize("hasAnyRole('USER','ADMIN','SUPER_ADMIN')")
@ConditionalOnProperty(name="safeai.erasure.approvals.enabled",havingValue="true")
public class ErasureSelfReviewController {
    private final ErasureReviewService service;
    public ErasureSelfReviewController(ErasureReviewService service) { this.service=service; }

    @GetMapping("/{id}/review")
    public ReviewView read(@PathVariable UUID id,
            @AuthenticationPrincipal SafeAiUserPrincipal actor) {
        return service.readForRequester(id,actor);
    }

    @GetMapping("/{id}/review-events")
    public ReviewHistory events(@PathVariable UUID id,
            @AuthenticationPrincipal SafeAiUserPrincipal actor) {
        return service.history(id,actor,false);
    }

    @PostMapping("/{id}/provide-information")
    public ReviewView provideInformation(@PathVariable UUID id,
            @RequestHeader("If-Match") String tag,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal SafeAiUserPrincipal actor) {
        return service.provideInformation(id,ErasureReviewController.version(tag),key,actor);
    }
}
