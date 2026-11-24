// SafeAI-Desk/backend/src/main/java/ru/safeai/gateway/erasure/ErasureCaseController.java
package ru.safeai.gateway.erasure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.erasure.ErasureCaseTypes.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/erasure")
@PreAuthorize("hasAnyRole('USER', 'ADMIN', 'SUPER_ADMIN')")
@ConditionalOnProperty(name = "safeai.erasure.requests.enabled", havingValue = "true")
public class ErasureCaseController {
    private final ErasureCaseService service;
    private final ErasureCaseMutations mutations;

    public ErasureCaseController(ErasureCaseService service,
            ErasureCaseMutations mutations) {
        this.service = service;
        this.mutations = mutations;
    }

    @PostMapping("/previews")
    public Preview preview(@RequestBody Target request,
            @AuthenticationPrincipal(errorOnInvalidType = true) SafeAiUserPrincipal actor) {
        return service.preview(request, actor);
    }

    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.CREATED)
    public CaseView create(@RequestBody DraftCreate request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @AuthenticationPrincipal(errorOnInvalidType = true) SafeAiUserPrincipal actor) {
        return mutations.create(request, idempotencyKey, actor);
    }

    @GetMapping("/requests")
    public CasePage mine(@AuthenticationPrincipal(errorOnInvalidType = true)
            SafeAiUserPrincipal actor) {
        return service.mine(actor);
    }

    @GetMapping("/requests/{id}")
    public CaseView get(@PathVariable UUID id,
            @AuthenticationPrincipal(errorOnInvalidType = true) SafeAiUserPrincipal actor) {
        return service.get(id, actor);
    }

    @PostMapping("/requests/{id}/submit")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CaseView submit(@PathVariable UUID id, @RequestHeader("If-Match") String tag,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal(errorOnInvalidType = true) SafeAiUserPrincipal actor) {
        return mutations.submit(id, version(tag), key, actor);
    }

    @PostMapping("/requests/{id}/withdraw")
    public CaseView withdraw(@PathVariable UUID id, @RequestHeader("If-Match") String tag,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal(errorOnInvalidType = true) SafeAiUserPrincipal actor) {
        return mutations.withdraw(id, version(tag), key, actor);
    }

    private long version(String tag) {
        if (tag == null || !tag.matches("\"(0|[1-9][0-9]{0,17})\"")) {
            throw new BadRequestException("Неверный If-Match.");
        }
        try {
            return Long.parseLong(tag.substring(1, tag.length()-1));
        } catch (NumberFormatException ex) {
            throw new BadRequestException("Неверный If-Match.");
        }
    }
}
