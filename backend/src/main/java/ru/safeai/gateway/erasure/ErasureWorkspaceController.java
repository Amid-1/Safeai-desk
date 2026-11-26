package ru.safeai.gateway.erasure;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.util.UUID;

/** Read-only workspace endpoints. Existing mutation controllers remain unchanged. */
@RestController
public class ErasureWorkspaceController {
    private final ErasureWorkspaceReads reads;
    public ErasureWorkspaceController(ErasureWorkspaceReads reads) { this.reads = reads; }

    @GetMapping("/api/erasure/requests/capabilities")
    @PreAuthorize("hasAnyRole('USER','ADMIN','SUPER_ADMIN')")
    public ErasureWorkspaceReads.Capabilities capabilities(@AuthenticationPrincipal SafeAiUserPrincipal actor) {
        return reads.capabilities(actor);
    }

    @GetMapping("/api/admin/erasure/registry")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ErasureWorkspaceReads.Page registry(@AuthenticationPrincipal SafeAiUserPrincipal actor,
            @RequestParam(name="state",defaultValue="ALL") String state,
            @RequestParam(name="query",defaultValue="") String query,
            @RequestParam(name="page",defaultValue="0") int page) {
        return reads.registry(actor, false, state, query, page);
    }

    @GetMapping("/api/admin/erasure/review-queue")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ErasureWorkspaceReads.Page reviewQueue(@AuthenticationPrincipal SafeAiUserPrincipal actor,
            @RequestParam(name="state",defaultValue="ALL") String state,
            @RequestParam(name="query",defaultValue="") String query,
            @RequestParam(name="page",defaultValue="0") int page) {
        return reads.registry(actor, true, state, query, page);
    }
    @GetMapping("/api/admin/erasure/registry-summary")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ErasureWorkspaceReads.RegistrySummary registrySummary(@AuthenticationPrincipal SafeAiUserPrincipal actor) {
        return reads.registrySummary(actor);
    }

    @GetMapping("/api/admin/erasure/registry/{requestId}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ErasureWorkspaceReads.RegistryDetail registryDetail(@AuthenticationPrincipal SafeAiUserPrincipal actor,
            @PathVariable("requestId") UUID requestId) {
        return reads.registryDetail(actor, requestId);
    }

    @GetMapping("/api/admin/erasure/history")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ErasureWorkspaceReads.HistoryPage history(@AuthenticationPrincipal SafeAiUserPrincipal actor,
            @RequestParam(name="query",defaultValue="") String query, @RequestParam(name="page",defaultValue="0") int page) {
        return reads.history(actor,query,page);
    }
}
