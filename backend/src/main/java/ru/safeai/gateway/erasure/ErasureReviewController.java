package ru.safeai.gateway.erasure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.erasure.ErasureReviewTypes.*;

import java.util.UUID;

/** Appointed-reviewer actions only. No worker / deletion endpoint. */
@RestController
@RequestMapping("/api/admin/erasure/reviews")
@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
@ConditionalOnProperty(name="safeai.erasure.approvals.enabled",havingValue="true")
public class ErasureReviewController {
    private final ErasureReviewService service;
    public ErasureReviewController(ErasureReviewService service) { this.service = service; }

    @GetMapping("/overview")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ReviewSummary overview(@AuthenticationPrincipal SafeAiUserPrincipal actor) {
        return service.platformSummary(actor);
    }

    @GetMapping("/access")
    public ReviewAccess access(@AuthenticationPrincipal SafeAiUserPrincipal actor) {
        return service.access(actor);
    }

    @GetMapping
    public ReviewPage queue(@AuthenticationPrincipal SafeAiUserPrincipal actor) {
        return service.queue(actor);
    }

    @GetMapping("/{id}")
    public ReviewView get(@PathVariable UUID id,
            @AuthenticationPrincipal SafeAiUserPrincipal actor) {
        return service.readForReviewer(id,actor);
    }

    @GetMapping("/{id}/events")
    public ReviewHistory events(@PathVariable UUID id,
            @AuthenticationPrincipal SafeAiUserPrincipal actor) {
        return service.history(id,actor,true);
    }

    @PostMapping("/{id}/decision")
    public ReviewView decide(@PathVariable UUID id,
            @RequestHeader("If-Match") String tag,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody DecisionBody body,
            @AuthenticationPrincipal SafeAiUserPrincipal actor) {
        return service.decide(id,version(tag),key,body,actor);
    }

    @PostMapping("/{id}/holds")
    public ReviewView applyHold(@PathVariable UUID id,
            @RequestHeader("If-Match") String tag,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody HoldBody body,
            @AuthenticationPrincipal SafeAiUserPrincipal actor) {
        return service.hold(id,version(tag),key,body,actor);
    }

    @PostMapping("/{id}/holds/release")
    public ReviewView releaseHold(@PathVariable UUID id,
            @RequestHeader("If-Match") String tag,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal SafeAiUserPrincipal actor) {
        return service.releaseHold(id,version(tag),key,actor);
    }

    static long version(String tag) {
        if (tag == null || !tag.matches("\\\"(0|[1-9][0-9]{0,17})\\\""))
            throw new BadRequestException("Неверный If-Match.");
        try {
            return Long.parseLong(tag.substring(1,tag.length()-1));
        } catch (NumberFormatException e) {
            throw new BadRequestException("Неверный If-Match.");
        }
    }
}
