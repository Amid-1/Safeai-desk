// SafeAI-Desk/backend/src/test/java/ru/safeai/gateway/erasure/ErasureCaseRefactorTest.java
package ru.safeai.gateway.erasure;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.erasure.ErasureCaseTypes.CaseView;
import ru.safeai.gateway.erasure.ErasureCaseTypes.DraftCreate;
import ru.safeai.gateway.erasure.ErasureCaseTypes.Operation;
import ru.safeai.gateway.erasure.ErasureCaseTypes.ResourceType;
import ru.safeai.gateway.erasure.ErasureCaseTypes.Target;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ErasureCaseRefactorTest {

    private final UUID org = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final UUID resourceId = UUID.randomUUID();
    private final UUID previewId = UUID.randomUUID();
    private final UUID caseId = UUID.randomUUID();
    private final String key = "case-key-12345678";
    private final Target target = new Target(
            ResourceType.CHAT, resourceId, Operation.REDACT_CONTENT, "FULL_CHAT_CONTENT");
    private final SafeAiUserPrincipal actor = mock(SafeAiUserPrincipal.class);
    private final ErasureCaseAuthority authority = mock(ErasureCaseAuthority.class);
    private final ErasureCaseRepository repository = mock(ErasureCaseRepository.class);
    private final ErasureCaseReads reads = mock(ErasureCaseReads.class);
    private final ErasureCaseMutations mutations =
            new ErasureCaseMutations(authority, repository, reads);

    private void user() {
        when(actor.getOrganizationId()).thenReturn(org);
        when(actor.getId()).thenReturn(actorId);
    }

    private ErasureCaseReads.Row row(String state, long version, String submitKey,
            String withdrawKey, String requestDigest) {
        CaseView view = new CaseView(caseId, ResourceType.CHAT, resourceId,
                Operation.REDACT_CONTENT, "FULL_CHAT_CONTENT", state,
                version, Instant.now(), false, false);
        return new ErasureCaseReads.Row(view, org, actorId, previewId,
                "a".repeat(64), 7, key, requestDigest, submitKey, withdrawKey);
    }

    @Test
    void createRechecksLiveActorEvenForIdempotentReplay() {
        user();
        DraftCreate request = new DraftCreate(target, previewId, "a".repeat(64),
                7, "OWNER_REQUEST");
        ErasureCaseReads.Row existing = row("DRAFT", 0, null, null,
                ErasureCaseDigest.sha(request.toString()));
        when(reads.findByIdempotency(org, actorId, key)).thenReturn(existing);

        assertThat(mutations.create(request, key, actor)).isEqualTo(existing.view());
        var ordered = inOrder(authority, reads);
        ordered.verify(authority).requireActor(actor);
        ordered.verify(reads).findByIdempotency(org, actorId, key);
        verify(repository, never()).insertDraft(any(), any(), any(), any(),
                any(), any(), anyLong(), any(), any(), any());
    }

    @Test
    void matchingLivePreviewCreatesDraftAndOneEvent() {
        user();
        DraftCreate request = new DraftCreate(target, previewId, "a".repeat(64),
                7, "OWNER_REQUEST");
        ErasureCaseReads.Row draft = row("DRAFT", 0, null, null,
                ErasureCaseDigest.sha(request.toString()));
        when(authority.targetVersion(target, actor)).thenReturn(7L);
        when(repository.matchesPreview(previewId, org, actorId, target, 7L,
                request.previewDigest())).thenReturn(true);
        when(repository.insertDraft(any(), eq(org), eq(actorId), eq(target),
                eq(previewId), eq(request.previewDigest()), eq(7L),
                eq("OWNER_REQUEST"), eq(key), eq(draft.requestDigest()))).thenReturn(true);
        when(reads.find(any(), eq(org), eq(actorId), eq(false))).thenReturn(draft);

        assertThat(mutations.create(request, key, actor)).isEqualTo(draft.view());
        verify(repository).event(any(), eq(org), eq(actorId), eq("DRAFT_CREATED"), eq(0L));
    }

    @Test
    void missingOrExpiredPreviewFailsClosedWithoutInsertOrEvent() {
        user();
        DraftCreate request = new DraftCreate(target, previewId, "a".repeat(64),
                7, "OWNER_REQUEST");
        when(authority.targetVersion(target, actor)).thenReturn(7L);
        // The mocked repository returns false for a missing or expired preview.
        assertThatThrownBy(() -> mutations.create(request, key, actor))
                .isInstanceOf(ConflictException.class);
        verify(repository, never()).insertDraft(any(), any(), any(), any(),
                any(), any(), anyLong(), any(), any(), any());
        verify(repository, never()).event(any(), any(), any(), any(), anyLong());
    }

    @Test
    void submitRechecksPositivePreviewAndWritesTransitionAndEvent() {
        user();
        ErasureCaseReads.Row draft = row("DRAFT", 0, null, null, "b".repeat(64));
        ErasureCaseReads.Row submitted = row("SUBMITTED", 1, key, null, "b".repeat(64));
        when(reads.find(caseId, org, actorId, true)).thenReturn(draft);
        when(reads.find(caseId, org, actorId, false)).thenReturn(submitted);
        when(authority.targetVersion(target, actor)).thenReturn(7L);
        when(repository.matchesPreview(previewId, org, actorId, target, 7L,
                draft.previewDigest())).thenReturn(true);
        when(repository.transition(caseId, org, "DRAFT", "SUBMITTED", 0, key))
                .thenReturn(true);

        assertThat(mutations.submit(caseId, 0, key, actor)).isEqualTo(submitted.view());
        var ordered = inOrder(repository);
        ordered.verify(repository).transition(caseId, org, "DRAFT", "SUBMITTED", 0, key);
        ordered.verify(repository).event(caseId, org, actorId, "SUBMITTED", 1);
    }

    @Test
    void withdrawnSameKeyIsIdempotentButDifferentKeyIsRejected() {
        user();
        ErasureCaseReads.Row withdrawn = row("WITHDRAWN", 2, key, key, "b".repeat(64));
        when(reads.find(caseId, org, actorId, true)).thenReturn(withdrawn);

        assertThat(mutations.withdraw(caseId, 1, key, actor)).isEqualTo(withdrawn.view());
        assertThatThrownBy(() -> mutations.withdraw(
                caseId, 2, "different-key-123456", actor))
                .isInstanceOf(ConflictException.class);
        verify(repository, never()).transition(any(), any(), any(), any(), anyLong(), any());
        verify(repository, never()).event(any(), any(), any(), any(), anyLong());
    }

    @Test
    void unexpectedStateCannotTransitionToWithdrawn() {
        user();
        ErasureCaseReads.Row unexpected = row("UNKNOWN", 3, null, null, "b".repeat(64));
        when(reads.find(caseId, org, actorId, true)).thenReturn(unexpected);

        assertThatThrownBy(() -> mutations.withdraw(caseId, 3, key, actor))
                .isInstanceOf(ConflictException.class);
        verify(repository, never()).transition(any(), any(), any(), any(), anyLong(), any());
    }

    @Test
    void previewValidityUsesBooleanExistenceAndFailsClosedOnNull() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ErasureCaseRepository sqlRepository = new ErasureCaseRepository(jdbc);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class),
                eq(previewId), eq(org), eq(actorId),
                eq("CHAT"), eq(resourceId), eq("REDACT_CONTENT"),
                eq("FULL_CHAT_CONTENT"), eq(7L), eq("a".repeat(64))))
                .thenReturn(true, false, null);

        assertThat(sqlRepository.matchesPreview(previewId, org, actorId, target, 7,
                "a".repeat(64))).isTrue();
        assertThat(sqlRepository.matchesPreview(previewId, org, actorId, target, 7,
                "a".repeat(64))).isFalse();
        assertThat(sqlRepository.matchesPreview(previewId, org, actorId, target, 7,
                "a".repeat(64))).isFalse();
        verify(jdbc, times(3)).queryForObject(argThat(sql -> sql.contains("SELECT EXISTS")
                        && sql.contains("organization_id = ?")
                        && sql.contains("requester_user_id = ?")
                        && sql.contains("expires_at > transaction_timestamp()")),
                eq(Boolean.class), eq(previewId), eq(org), eq(actorId),
                eq("CHAT"), eq(resourceId), eq("REDACT_CONTENT"),
                eq("FULL_CHAT_CONTENT"), eq(7L), eq("a".repeat(64)));
    }
}
