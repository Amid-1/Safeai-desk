package ru.safeai.gateway.model.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.model.domain.OrganizationModelPolicy;
import ru.safeai.gateway.model.dto.CreateOrganizationModelPolicyVersionRequest;
import ru.safeai.gateway.model.dto.ModelPolicyPreviewResponse;
import ru.safeai.gateway.model.repository.ModelPolicyPreviewConfirmationRepository;
import ru.safeai.gateway.model.repository.OrganizationModelPolicyRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Подтверждение относится к сохранению правил, но не гарантирует будущий AI-запрос. */
@Service
public class ModelPolicyPreviewConfirmationService {
    private final ModelPolicyPreviewService simulator;
    private final ModelPolicyPreviewConfirmationRepository confirmations;
    private final OrganizationModelPolicyRepository policies;
    private final Clock clock;

    public ModelPolicyPreviewConfirmationService(ModelPolicyPreviewService simulator,
            ModelPolicyPreviewConfirmationRepository confirmations,
            OrganizationModelPolicyRepository policies, Clock clock) {
        this.simulator = simulator;
        this.confirmations = confirmations;
        this.policies = policies;
        this.clock = clock;
    }

    @Transactional
    public ModelPolicyPreviewResponse preview(UUID org, CreateOrganizationModelPolicyVersionRequest request,
                                              SafeAiUserPrincipal actor) {
        // Симулятор сам проверяет RBAC, tenant, версию и семантику черновика.
        ModelPolicyPreviewResponse result = simulator.preview(org, request, actor);
        if (!policies.lockOrganization(result.organizationId()) ||
                policies.findLatest(result.organizationId()).map(OrganizationModelPolicy::version).orElse(0)
                        != result.basePolicyVersion()) {
            throw invalid();
        }
        UUID token = UUID.randomUUID();
        Instant expires = DatabaseTimestampNormalizer.normalize(clock.instant()).plusSeconds(300);
        confirmations.issue(result.organizationId(), actor.getId(), ModelPolicyPreviewFingerprint.token(token),
                ModelPolicyPreviewFingerprint.draft(request), ModelPolicyPreviewFingerprint.context(result),
                result.wouldLockOutOrganization(), expires);
        return result.withConfirmation(token, expires);
    }

    /** Вызывается после tenant lock; идентичный retry возвращает результат, без нового аудита. */
    @Transactional(propagation = Propagation.MANDATORY)
    public OrganizationModelPolicy retry(UUID org, CreateOrganizationModelPolicyVersionRequest request,
                                         SafeAiUserPrincipal actor) {
        var proof = requireProof(org, request, actor);
        if (proof.resultPolicyId() == null) return null;
        return policies.findById(org, proof.resultPolicyId()).orElseThrow(ModelPolicyPreviewConfirmationService::invalid);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireCurrent(UUID org, CreateOrganizationModelPolicyVersionRequest request,
                               SafeAiUserPrincipal actor) {
        var proof = requireProof(org, request, actor);
        if (proof.resultPolicyId() != null) throw invalid();
        var current = simulator.preview(org, request, actor);
        if (!proof.contextHash().equals(ModelPolicyPreviewFingerprint.context(current))) throw invalid();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(UUID org, CreateOrganizationModelPolicyVersionRequest request,
                         SafeAiUserPrincipal actor, UUID policyId) {
        if (!confirmations.complete(org, actor.getId(), ModelPolicyPreviewFingerprint.token(request.previewConfirmationId()),
                policyId, clock.instant())) throw invalid();
    }

    private ModelPolicyPreviewConfirmationRepository.Confirmation requireProof(UUID org,
            CreateOrganizationModelPolicyVersionRequest request, SafeAiUserPrincipal actor) {
        if (request.previewConfirmationId() == null) throw invalid();
        var proof = confirmations.lock(org, actor.getId(), ModelPolicyPreviewFingerprint.token(request.previewConfirmationId()))
                .orElseThrow(ModelPolicyPreviewConfirmationService::invalid);
        if (!clock.instant().isBefore(proof.expiresAt()) ||
                !proof.draftHash().equals(ModelPolicyPreviewFingerprint.draft(request))) throw invalid();
        if (proof.lockout() != request.acknowledgeLockout()) {
            throw new ConflictException(proof.lockout()
                    ? "Подтвердите осознанную блокировку всех моделей после проверки правил."
                    : "Подтверждение блокировки не соответствует результату проверки. Повторите проверку.");
        }
        return proof;
    }

    @Scheduled(fixedDelay = 60000)
    public void purgeExpired() {
        confirmations.purgeExpired(clock.instant());
    }

    private static ConflictException invalid() {
        return new ConflictException("Подтверждение проверки правил отсутствует, устарело или не соответствует настройкам. Повторите проверку правил.");
    }
}
