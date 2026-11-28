// SafeAI-Desk/backend/src/test/java/ru/safeai/gateway/erasure/ErasureCaseServiceTest.java
package ru.safeai.gateway.erasure;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.erasure.ErasureCaseTypes.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ErasureCaseServiceTest {
    private final UUID org = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();
    private final SafeAiUserPrincipal actor = mock(SafeAiUserPrincipal.class);
    private final ErasureCaseAuthority authority = mock(ErasureCaseAuthority.class);
    private final ErasureCaseRepository writes = mock(ErasureCaseRepository.class);
    private final ErasureCaseReads reads = mock(ErasureCaseReads.class);
    private final ErasureCaseService service = new ErasureCaseService(authority, writes, reads);
    private final ErasureCaseMutations mutations = new ErasureCaseMutations(authority, writes, reads);
    private final Target target = new Target(ResourceType.CHAT, UUID.randomUUID(),
            Operation.REDACT_CONTENT, "FULL_CHAT_CONTENT");

    private void authenticated() {
        when(actor.getOrganizationId()).thenReturn(org);
        when(actor.getId()).thenReturn(user);
    }

    private ErasureCaseReads.Row row(String state, long version) {
        CaseView view = new CaseView(UUID.randomUUID(), target.resourceType(),
                target.resourceId(), target.operation(), target.scope(), state,
                version, Instant.now(), false, false);
        return new ErasureCaseReads.Row(view, org, user, UUID.randomUUID(),
                "a".repeat(64), 7, "case-123456789", "b".repeat(64),
                "submit-123456789", "withdraw-123456789");
    }

    @Test void previewNeverCallsExecutionWorker() {
        authenticated();
        when(authority.targetVersion(target, actor)).thenReturn(7L);
        when(writes.insertPreview(any(), eq(org), eq(user), eq(target),
                eq(7L), anyString())).thenReturn(Instant.now().plusSeconds(600));
        Preview result = service.preview(target, actor);
        assertThat(result.executionAvailable()).isFalse();
        assertThat(result.expectedResourceVersion()).isEqualTo(7);
        assertThat(result.previewDigest()).matches("[a-f0-9]{64}");
        verify(writes).insertPreview(eq(result.previewId()), eq(org),
                eq(user), eq(target), eq(7L), eq(result.previewDigest()));
        verifyNoInteractions(reads);
    }

    @Test void listIsAlwaysScopedToRequesterAndTenant() {
        authenticated();
        when(reads.listSelf(org, user)).thenReturn(List.of());
        assertThat(service.mine(actor).items()).isEmpty();
        verify(reads).listSelf(org, user);
        verify(reads, never()).listTenant(any());
    }

    @Test void tenantQueueRequiresIndependentLiveAdminCheck() {
        authenticated();
        assertThatThrownBy(() -> service.tenantQueue(actor))
                .isInstanceOf(ForbiddenOperationException.class);
        verify(reads, never()).listTenant(any());
        when(authority.tenantAdmin(actor)).thenReturn(true);
        assertThat(service.tenantQueue(actor).items()).isEmpty();
        verify(reads).listTenant(org);
    }

    @Test void createFailsOnStalePreviewAndDoesNotInsert() {
        authenticated();
        DraftCreate draft = new DraftCreate(target, UUID.randomUUID(),
                "a".repeat(64), 7, "OWNER_REQUEST");
        when(authority.targetVersion(target, actor)).thenReturn(8L);
        assertThatThrownBy(() -> mutations.create(draft, "case-123456789", actor))
                .isInstanceOf(ConflictException.class);
        verify(writes, never()).insertDraft(any(), any(), any(), any(),
                any(), any(), anyLong(), any(), any(), any());
    }

    @Test void createWithSameKeyAndSameBodyReturnsExistingCase() {
        authenticated();
        DraftCreate draft = new DraftCreate(target, UUID.randomUUID(),
                "a".repeat(64), 7, "OWNER_REQUEST");
        ErasureCaseReads.Row old = row("DRAFT", 0);
        old = new ErasureCaseReads.Row(old.view(), org, user, old.previewId(),
                old.previewDigest(), 7, "case-123456789",
                ErasureCaseDigest.sha(draft.toString()), null, null);
        when(reads.findByIdempotency(org, user, "case-123456789")).thenReturn(old);
        assertThat(mutations.create(draft, "case-123456789", actor))
                .isEqualTo(old.view());
        verify(writes, never()).insertDraft(any(), any(), any(), any(),
                any(), any(), anyLong(), any(), any(), any());
    }

    @Test void submitRechecksResourceVersionAndWillNotExecute() {
        authenticated();
        ErasureCaseReads.Row draft = row("DRAFT", 0);
        when(reads.find(draft.view().requestId(), org, user, true)).thenReturn(draft);
        when(authority.targetVersion(target, actor)).thenReturn(8L);
        assertThatThrownBy(() -> mutations.submit(draft.view().requestId(), 0,
                "submit-123456789", actor)).isInstanceOf(ConflictException.class);
        verify(writes, never()).transition(any(), any(), any(), any(), anyLong(), any());
    }

    @Test void idempotentSubmitCannotRepeatStateTransition() {
        authenticated();
        ErasureCaseReads.Row submitted = row("SUBMITTED", 1);
        when(reads.find(submitted.view().requestId(), org, user, true))
                .thenReturn(submitted);
        assertThat(mutations.submit(submitted.view().requestId(), 0,
                "submit-123456789", actor)).isEqualTo(submitted.view());
        verify(writes, never()).transition(any(), any(), any(), any(), anyLong(), any());
    }

    @Test void withdrawDoesNotAcceptReusedKeyFromOtherAction() {
        authenticated();
        ErasureCaseReads.Row submitted = row("SUBMITTED", 1);
        when(reads.find(submitted.view().requestId(), org, user, true))
                .thenReturn(submitted);
        assertThatThrownBy(() -> mutations.withdraw(submitted.view().requestId(), 0,
                "another-123456789", actor)).isInstanceOf(ConflictException.class);
        verify(writes, never()).transition(any(), any(), any(), any(), anyLong(), any());
    }
}
