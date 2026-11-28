package ru.safeai.gateway.model.service;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.model.domain.BudgetEnforcement;
import ru.safeai.gateway.model.domain.ModelRouteOutcome;
import ru.safeai.gateway.model.domain.ModelRouteReason;
import ru.safeai.gateway.model.dto.CreateOrganizationModelPolicyVersionRequest;
import ru.safeai.gateway.model.dto.ModelPolicyPreviewResponse;
import ru.safeai.gateway.model.repository.ModelPolicyPreviewConfirmationRepository;
import ru.safeai.gateway.model.repository.OrganizationModelPolicyRepository;
import ru.safeai.gateway.model.testsupport.ModelTestFixtures;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ModelPolicyPreviewConfirmationServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-11-20T12:00:00Z");

    private static final UUID ORG =
            ModelTestFixtures.ORGANIZATION_ID;

    private static final UUID TOKEN = UUID.randomUUID();

    private final ModelPolicyPreviewService simulator =
            mock(ModelPolicyPreviewService.class);

    private final ModelPolicyPreviewConfirmationRepository repository =
            mock(ModelPolicyPreviewConfirmationRepository.class);

    private final OrganizationModelPolicyRepository policies =
            mock(OrganizationModelPolicyRepository.class);

    private final ModelPolicyPreviewConfirmationService service =
            new ModelPolicyPreviewConfirmationService(
                    simulator,
                    repository,
                    policies,
                    Clock.fixed(NOW, ZoneOffset.UTC)
            );

    private final SafeAiUserPrincipal user =
            ModelTestFixtures.adminPrincipal();

    @Test
    void issuesRandomScopedProofWithoutStoringRawToken() {
        var draft = request(null, false, false);
        var result = preview(false, false, "mock");

        when(simulator.preview(ORG, draft, user))
                .thenReturn(result);

        when(policies.lockOrganization(ORG))
                .thenReturn(true);

        when(policies.findLatest(ORG))
                .thenReturn(Optional.empty());

        var issued = service.preview(ORG, draft, user);

        assertThat(issued.previewConfirmationId()).isNotNull();

        assertThat(issued.confirmationExpiresAt())
                .isEqualTo(NOW.plusSeconds(300));

        verify(repository).issue(
                eq(ORG),
                eq(user.getId()),
                eq(ModelPolicyPreviewFingerprint.token(
                        issued.previewConfirmationId()
                )),
                eq(ModelPolicyPreviewFingerprint.draft(draft)),
                eq(ModelPolicyPreviewFingerprint.context(result)),
                eq(false),
                eq(NOW.plusSeconds(300))
        );

        assertThat(issued.toString())
                .doesNotContain(
                        issued.previewConfirmationId().toString()
                );
    }

    @Test
    void rejectsMissingForgedExpiredOrForeignProofEvenForDisabledRules() {
        assertThatThrownBy(() ->
                service.retry(
                        ORG,
                        request(null, false, false),
                        user
                ))
                .isInstanceOf(ConflictException.class);

        var draft = request(TOKEN, false, false);

        when(repository.lock(
                ORG,
                user.getId(),
                ModelPolicyPreviewFingerprint.token(TOKEN)
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.retry(ORG, draft, user))
                .isInstanceOf(ConflictException.class);

        stored(draft, preview(false, false, "mock"), NOW, null);

        assertThatThrownBy(() -> service.retry(ORG, draft, user))
                .isInstanceOf(ConflictException.class);

        stored(
                draft,
                preview(false, false, "mock"),
                NOW.plusSeconds(300),
                null
        );

        assertThatThrownBy(() ->
                service.retry(
                        ModelTestFixtures.OTHER_ORGANIZATION_ID,
                        draft,
                        user
                ))
                .isInstanceOf(ConflictException.class);

        verifyNoInteractions(simulator);
    }

    @Test
    void rejectsChangedDraftAndRequiresAcknowledgementBoundToLockout() {
        var draft = request(TOKEN, true, false);

        stored(
                draft,
                preview(true, true, "mock"),
                NOW.plusSeconds(300),
                null
        );

        assertThatThrownBy(() -> service.retry(ORG, draft, user))
                .isInstanceOf(ConflictException.class);

        assertThatThrownBy(() ->
                service.retry(
                        ORG,
                        request(TOKEN, true, null),
                        user
                ))
                .isInstanceOf(ConflictException.class);

        var confirmed = request(TOKEN, true, true);

        assertThat(service.retry(ORG, confirmed, user))
                .isNull();

        assertThatThrownBy(() ->
                service.retry(
                        ORG,
                        request(TOKEN, false, true),
                        user
                ))
                .isInstanceOf(ConflictException.class);

        stored(
                draft,
                preview(true, false, "mock"),
                NOW.plusSeconds(300),
                null
        );

        assertThatThrownBy(() ->
                service.retry(ORG, confirmed, user))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void recomputesContextAndRejectsChangesWithoutCompletingProof() {
        var draft = request(TOKEN, true, false);
        var initial = preview(true, false, "mock");

        stored(
                draft,
                initial,
                NOW.plusSeconds(300),
                null
        );

        when(simulator.preview(ORG, draft, user))
                .thenReturn(preview(true, false, "different"));

        assertThatThrownBy(() ->
                service.requireCurrent(ORG, draft, user))
                .isInstanceOf(ConflictException.class);

        verify(repository, never())
                .complete(any(), any(), any(), any(), any());

        when(simulator.preview(ORG, draft, user))
                .thenReturn(initial);

        service.requireCurrent(ORG, draft, user);
    }

    @Test
    void completionIsFailClosedAndOnlyOneVersionMayConsumeProof() {
        var draft = request(TOKEN, true, false);
        UUID result = UUID.randomUUID();

        assertThatThrownBy(() ->
                service.complete(ORG, draft, user, result))
                .isInstanceOf(ConflictException.class);

        when(repository.complete(
                ORG,
                user.getId(),
                ModelPolicyPreviewFingerprint.token(TOKEN),
                result,
                NOW
        )).thenReturn(true, false);

        service.complete(ORG, draft, user, result);

        assertThatThrownBy(() ->
                service.complete(ORG, draft, user, result))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void retryReturnsPreviouslyCreatedPolicyWithoutCreatingAnotherVersion() {
        var draft = request(TOKEN, true, false);
        var policy = ModelTestFixtures.hardPolicy();

        stored(
                draft,
                preview(true, false, "mock"),
                NOW.plusSeconds(300),
                policy.id()
        );

        when(policies.findById(ORG, policy.id()))
                .thenReturn(Optional.of(policy));

        var result = service.retry(ORG, draft, user);

        assertThat(result).isSameAs(policy);
        assertThat(result.id()).isEqualTo(policy.id());

        verify(policies).findById(ORG, policy.id());

        verify(repository, never())
                .complete(any(), any(), any(), any(), any());

        verifyNoInteractions(simulator);
    }

    @Test
    void consumedProofCannotAuthorizeAnotherPolicyVersion() {
        var draft = request(TOKEN, true, false);

        stored(
                draft,
                preview(true, false, "mock"),
                NOW.plusSeconds(300),
                UUID.randomUUID()
        );

        assertThatThrownBy(() ->
                service.requireCurrent(ORG, draft, user))
                .isInstanceOf(ConflictException.class);

        verify(repository, never())
                .complete(any(), any(), any(), any(), any());

        verifyNoInteractions(simulator, policies);
    }

    @Test
    void retryRejectsConsumedProofWhenResultPolicyDoesNotExist() {
        var draft = request(TOKEN, true, false);
        UUID policyId = UUID.randomUUID();

        stored(
                draft,
                preview(true, false, "mock"),
                NOW.plusSeconds(300),
                policyId
        );

        when(policies.findById(ORG, policyId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.retry(ORG, draft, user))
                .isInstanceOf(ConflictException.class);

        verify(policies).findById(ORG, policyId);

        verify(repository, never())
                .complete(any(), any(), any(), any(), any());

        verifyNoInteractions(simulator);
    }

    private void stored(
            CreateOrganizationModelPolicyVersionRequest request,
            ModelPolicyPreviewResponse result,
            Instant expires,
            UUID policyId
    ) {
        when(repository.lock(
                ORG,
                user.getId(),
                ModelPolicyPreviewFingerprint.token(TOKEN)
        )).thenReturn(Optional.of(
                new ModelPolicyPreviewConfirmationRepository.Confirmation(
                        ModelPolicyPreviewFingerprint.draft(request),
                        ModelPolicyPreviewFingerprint.context(result),
                        result.wouldLockOutOrganization(),
                        expires,
                        policyId
                )
        ));
    }

    private static CreateOrganizationModelPolicyVersionRequest request(
            UUID token,
            boolean enabled,
            Boolean acknowledgeLockout
    ) {
        return new CreateOrganizationModelPolicyVersionRequest(
                0,
                enabled,
                Set.of(),
                Set.of(),
                null,
                null,
                null,
                null,
                null,
                BudgetEnforcement.SOFT,
                false,
                false,
                false,
                null,
                token,
                acknowledgeLockout
        );
    }

    private static ModelPolicyPreviewResponse preview(
            boolean enabled,
            boolean lockout,
            String provider
    ) {
        return new ModelPolicyPreviewResponse(
                ORG,
                0,
                enabled,
                NOW,
                provider,
                "mock-safeai",
                lockout
                        ? ModelRouteOutcome.DENIED
                        : ModelRouteOutcome.ALLOWED,
                lockout
                        ? ModelRouteReason.MODEL_NOT_ALLOWED
                        : ModelRouteReason.RUNTIME_ONLY_MATCH,
                null,
                0,
                lockout,
                List.of()
        );
    }
}
