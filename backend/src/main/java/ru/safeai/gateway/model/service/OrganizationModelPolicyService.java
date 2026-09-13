package ru.safeai.gateway.model.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.model.domain.OrganizationModelPolicy;
import ru.safeai.gateway.model.dto.CreateOrganizationModelPolicyVersionRequest;
import ru.safeai.gateway.model.dto.OrganizationModelPolicyResponse;
import ru.safeai.gateway.model.repository.OrganizationModelPolicyRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class OrganizationModelPolicyService {

    private final OrganizationModelPolicyRepository repository;
    private final AuditEventService audit;
    private final Clock clock;

    public OrganizationModelPolicyService(
            OrganizationModelPolicyRepository repository,
            AuditEventService audit,
            Clock clock
    ) {
        this.repository = Objects.requireNonNull(
                repository,
                "repository не должен быть null"
        );
        this.audit = Objects.requireNonNull(
                audit,
                "audit не должен быть null"
        );
        this.clock = Objects.requireNonNull(
                clock,
                "clock не должен быть null"
        );
    }

    @Transactional(readOnly = true)
    public OrganizationModelPolicyResponse current(
            UUID organizationId,
            SafeAiUserPrincipal currentUser
    ) {
        UUID target = resolveVisibleOrganization(
                organizationId,
                currentUser
        );

        if (!repository.organizationExists(target)) {
            throw new ResourceNotFoundException(
                    "Организация не найдена: " + target
            );
        }

        return repository.findLatest(target)
                .map(OrganizationModelPolicyResponse::from)
                .orElseGet(() ->
                        OrganizationModelPolicyResponse.unconfigured(
                                target
                        )
                );
    }

    @Transactional
    public OrganizationModelPolicyResponse createVersion(
            UUID organizationId,
            CreateOrganizationModelPolicyVersionRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );

        UUID target = resolveMutableOrganization(
                organizationId,
                currentUser
        );

        if (!repository.lockOrganization(target)) {
            throw new ResourceNotFoundException(
                    "Организация не найдена: " + target
            );
        }

        int previousVersion = repository.findLatest(target)
                .map(OrganizationModelPolicy::version)
                .orElse(0);

        if (previousVersion != request.expectedPreviousVersion()) {
            throw new ConflictException(
                    "Model policy version conflict: expected previous version "
                            + request.expectedPreviousVersion()
                            + ", actual "
                            + previousVersion
            );
        }

        Set<String> allow = OrganizationModelPolicyRules
                .normalizeModelKeys(request.allowModelKeys());
        Set<String> deny = OrganizationModelPolicyRules
                .normalizeModelKeys(request.denyModelKeys());
        String defaultModelKey = OrganizationModelPolicyRules
                .normalizeNullableModelKey(request.defaultModelKey());
        BigDecimal maxRequestCostUsd = OrganizationModelPolicyRules
                .normalizeMoney(
                        request.maxRequestCostUsd(),
                        "maxRequestCostUsd"
                );
        BigDecimal monthlyBudgetUsd = OrganizationModelPolicyRules
                .normalizeMoney(
                        request.monthlyBudgetUsd(),
                        "monthlyBudgetUsd"
                );

        OrganizationModelPolicyRules.validate(
                allow,
                deny,
                defaultModelKey,
                request.maxInputTokens(),
                request.maxOutputTokens(),
                maxRequestCostUsd,
                monthlyBudgetUsd,
                request.budgetEnforcement()
        );

        Instant now = clock.instant();
        OrganizationModelPolicy policy = new OrganizationModelPolicy(
                UUID.randomUUID(),
                target,
                previousVersion + 1,
                request.enabled(),
                allow,
                deny,
                defaultModelKey,
                request.maxInputTokens(),
                request.maxOutputTokens(),
                maxRequestCostUsd,
                monthlyBudgetUsd,
                request.budgetEnforcement(),
                request.requireCompletePricing(),
                request.requireNoTraining(),
                request.requireZeroDataRetention(),
                currentUser.getId(),
                now
        );

        repository.insert(policy);
        audit.record(
                currentUser,
                policy.organizationId(),
                AuditEventType.MODEL_POLICY_VERSION_CREATED,
                OrganizationModelPolicyAuditDetailsFactory.create(policy)
        );

        return OrganizationModelPolicyResponse.from(policy);
    }

    private static UUID resolveVisibleOrganization(
            UUID requested,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                currentUser,
                "currentUser не должен быть null"
        );
        ModelControlPlaneAccess.requireAdminOrSuperAdmin(
                currentUser,
                "Недостаточно прав для model policy"
        );

        UUID target = requested == null
                ? currentUser.getOrganizationId()
                : requested;

        if (isCrossTenantAccess(currentUser, target)) {
            throw new ForbiddenOperationException(
                    "Нельзя просматривать model policy другой организации"
            );
        }
        return target;
    }

    private static UUID resolveMutableOrganization(
            UUID requested,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                requested,
                "organizationId не должен быть null"
        );
        Objects.requireNonNull(
                currentUser,
                "currentUser не должен быть null"
        );
        ModelControlPlaneAccess.requireAdminOrSuperAdmin(
                currentUser,
                "Недостаточно прав для model policy"
        );

        if (isCrossTenantAccess(currentUser, requested)) {
            throw new ForbiddenOperationException(
                    "ADMIN может изменять model policy только своей организации"
            );
        }
        return requested;
    }

    private static boolean isCrossTenantAccess(
            SafeAiUserPrincipal currentUser,
            UUID targetOrganizationId
    ) {
        return ModelControlPlaneAccess.isTenantScopeRestricted(currentUser)
                && !currentUser.getOrganizationId()
                .equals(targetOrganizationId);
    }
}
