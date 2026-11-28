// SafeAI-Desk/backend/src/main/java/ru/safeai/gateway/erasure/ErasureCaseService.java
package ru.safeai.gateway.erasure;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.erasure.ErasureCaseTypes.*;
import java.time.Instant;
import java.util.UUID;

@Service
public class ErasureCaseService {
    private final ErasureCaseAuthority authority;
    private final ErasureCaseRepository repository;
    private final ErasureCaseReads reads;
    public ErasureCaseService(ErasureCaseAuthority authority,
            ErasureCaseRepository repository, ErasureCaseReads reads) {
        this.authority = authority; this.repository = repository; this.reads = reads;
    }
    @Transactional
    public Preview preview(Target target, SafeAiUserPrincipal actor) {
        ErasureCaseRules.validate(target);
        long version = authority.targetVersion(target, actor);
        UUID id = UUID.randomUUID();
        String digest = ErasureCaseDigest.sha(id + ":" + actor.getId() + ":" + actor.getOrganizationId()
                + ":" + target + ":" + version);
        Instant expiresAt = repository.insertPreview(id, actor.getOrganizationId(),
                actor.getId(), target, version, digest);
        return new Preview(id, digest, version, expiresAt, false);
    }

    @Transactional
    public CasePage mine(SafeAiUserPrincipal actor) {
        authority.requireActor(actor);
        return new CasePage(reads.listSelf(actor.getOrganizationId(), actor.getId()));
    }

    @Transactional
    public CasePage tenantQueue(SafeAiUserPrincipal actor) {
        if (!authority.tenantAdmin(actor)) throw new ForbiddenOperationException("Нет доступа.");
        return new CasePage(reads.listTenant(actor.getOrganizationId()));
    }

    @Transactional
    public CaseView get(UUID id, SafeAiUserPrincipal actor) {
        authority.requireActor(actor);
        return require(id, actor).view();
    }

    private ErasureCaseReads.Row require(UUID id, SafeAiUserPrincipal actor) {
        if (id == null) throw new ResourceNotFoundException("Заявка не найдена.");
        var row = reads.find(id, actor.getOrganizationId(), actor.getId(), false);
        if (row == null) throw new ResourceNotFoundException("Заявка не найдена.");
        return row;
    }
}
