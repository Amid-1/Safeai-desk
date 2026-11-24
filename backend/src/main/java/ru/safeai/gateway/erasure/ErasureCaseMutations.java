// SafeAI-Desk/backend/src/main/java/ru/safeai/gateway/erasure/ErasureCaseMutations.java
package ru.safeai.gateway.erasure;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.erasure.ErasureCaseTypes.CaseView;
import ru.safeai.gateway.erasure.ErasureCaseTypes.DraftCreate;
import ru.safeai.gateway.erasure.ErasureCaseTypes.Target;

import java.util.UUID;

/** R1a case intake only. Does not dispatch deletion workers. */
@Service
public class ErasureCaseMutations {

    private final ErasureCaseAuthority authority;
    private final ErasureCaseRepository repository;
    private final ErasureCaseReads reads;

    public ErasureCaseMutations(
            ErasureCaseAuthority authority,
            ErasureCaseRepository repository,
            ErasureCaseReads reads
    ) {
        this.authority = authority;
        this.repository = repository;
        this.reads = reads;
    }

    @Transactional
    public CaseView create(DraftCreate request, String key, SafeAiUserPrincipal actor) {
        if (request == null || request.previewId() == null
                || request.expectedResourceVersion() < 0) {
            throw invalid();
        }

        ErasureCaseRules.validate(request.target());
        ErasureCaseRules.reason(request.reasonCode());
        ErasureCaseRules.digest(request.previewDigest());
        ErasureCaseRules.key(key);

        // Recheck live actor state even when the idempotency key already exists.
        authority.requireActor(actor);
        UUID organizationId = actor.getOrganizationId();
        UUID actorId = actor.getId();
        String digest = ErasureCaseDigest.sha(request.toString());

        ErasureCaseReads.Row existing = reads.findByIdempotency(organizationId, actorId, key);
        if (existing != null) {
            return duplicate(existing, digest);
        }

        long liveVersion = authority.targetVersion(request.target(), actor);
        // Keep the positive SQL predicate. A stale or missing preview fails closed.
        if (liveVersion == request.expectedResourceVersion()
                && repository.matchesPreview(
                        request.previewId(), organizationId, actorId,
                        request.target(), liveVersion, request.previewDigest())) {
            return insertVerifiedDraft(request, key, digest, actor, liveVersion);
        }
        throw new ConflictException("Предпросмотр устарел. Повторите проверку.");
    }

    private CaseView insertVerifiedDraft(
            DraftCreate request,
            String key,
            String digest,
            SafeAiUserPrincipal actor,
            long liveVersion
    ) {
        UUID organizationId = actor.getOrganizationId();
        UUID actorId = actor.getId();
        UUID requestId = UUID.randomUUID();
        boolean inserted = repository.insertDraft(
                requestId, organizationId, actorId, request.target(),
                request.previewId(), request.previewDigest(), liveVersion,
                request.reasonCode(), key, digest);

        if (!inserted) {
            // ON CONFLICT DO NOTHING covers both reused keys and reused preview IDs.
            ErasureCaseReads.Row existing = reads.findByIdempotency(organizationId, actorId, key);
            if (existing != null) {
                return duplicate(existing, digest);
            }
            throw new ConflictException("Предпросмотр уже использован.");
        }

        repository.event(requestId, organizationId, actorId, "DRAFT_CREATED", 0);
        return require(requestId, actor, false).view();
    }

    @Transactional
    public CaseView submit(UUID id, long expectedVersion, String key, SafeAiUserPrincipal actor) {
        ErasureCaseRules.key(key);
        authority.requireActor(actor);
        ErasureCaseReads.Row row = require(id, actor, true);
        String state = row.view().state();

        if ("SUBMITTED".equals(state) && key.equals(row.submitKey())) {
            return row.view();
        }
        if (row.view().version() != expectedVersion || !"DRAFT".equals(state)) {
            throw changed();
        }

        Target target = new Target(
                row.view().resourceType(), row.view().resourceId(),
                row.view().operation(), row.view().scope());
        long liveVersion = authority.targetVersion(target, actor);

        if (liveVersion == row.resourceVersion()
                && repository.matchesPreview(
                        row.previewId(), actor.getOrganizationId(), actor.getId(),
                        target, liveVersion, row.previewDigest())) {
            return submitVerifiedDraft(id, expectedVersion, key, actor);
        }
        throw changed();
    }

    private CaseView submitVerifiedDraft(
            UUID requestId,
            long expectedVersion,
            String key,
            SafeAiUserPrincipal actor
    ) {
        UUID organizationId = actor.getOrganizationId();
        if (!repository.transition(
                requestId, organizationId, "DRAFT", "SUBMITTED", expectedVersion, key)) {
            throw changed();
        }
        repository.event(requestId, organizationId, actor.getId(),
                "SUBMITTED", expectedVersion + 1);
        return require(requestId, actor, false).view();
    }

    @Transactional
    public CaseView withdraw(UUID id, long expectedVersion, String key, SafeAiUserPrincipal actor) {
        ErasureCaseRules.key(key);
        authority.requireActor(actor);
        ErasureCaseReads.Row row = require(id, actor, true);
        String state = row.view().state();

        // The same withdrawal can be retried; a different key must not replay it.
        if ("WITHDRAWN".equals(state)) {
            if (key.equals(row.withdrawKey())) {
                return row.view();
            }
            throw changed();
        }
        if (row.view().version() != expectedVersion
                || (!"DRAFT".equals(state) && !"SUBMITTED".equals(state))) {
            throw changed();
        }

        UUID organizationId = actor.getOrganizationId();
        if (!repository.transition(id, organizationId, state, "WITHDRAWN", expectedVersion, key)) {
            throw changed();
        }
        repository.event(id, organizationId, actor.getId(),
                "WITHDRAWN", expectedVersion + 1);
        return require(id, actor, false).view();
    }

    private ErasureCaseReads.Row require(UUID id, SafeAiUserPrincipal actor, boolean lock) {
        if (id == null) {
            throw new ResourceNotFoundException("Заявка не найдена.");
        }
        ErasureCaseReads.Row row = reads.find(id, actor.getOrganizationId(), actor.getId(), lock);
        if (row == null) {
            throw new ResourceNotFoundException("Заявка не найдена.");
        }
        return row;
    }

    private CaseView duplicate(ErasureCaseReads.Row row, String digest) {
        if (!row.requestDigest().equals(digest)) {
            throw new ConflictException("Ключ повтора уже используется другим запросом.");
        }
        return row.view();
    }

    private ConflictException changed() {
        return new ConflictException("Заявка уже изменилась. Обновите страницу.");
    }

    private BadRequestException invalid() {
        return new BadRequestException("Некорректная заявка.");
    }
}
