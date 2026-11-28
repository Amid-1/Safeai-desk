package ru.safeai.gateway.erasure;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.erasure.ErasureCaseTypes.*;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ErasureCaseHoldWithdrawalTest {
    private final UUID org = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final UUID caseId = UUID.randomUUID();
    private final UUID resourceId = UUID.randomUUID();
    private final UUID previewId = UUID.randomUUID();
    private final SafeAiUserPrincipal actor = mock(SafeAiUserPrincipal.class);
    private final ErasureCaseAuthority authority = mock(ErasureCaseAuthority.class);
    private final ErasureCaseReads reads = mock(ErasureCaseReads.class);
    private final ErasureCaseRepository repository = mock(ErasureCaseRepository.class);
    private final ErasureCaseMutations mutations = new ErasureCaseMutations(authority, repository, reads);

    private ErasureCaseReads.Row row(String state, long version) {
        var view = new CaseView(caseId, ResourceType.CHAT, resourceId, Operation.REDACT_CONTENT,
                "FULL_CHAT_CONTENT", state, version, Instant.now(), false, false);
        return new ErasureCaseReads.Row(view, org, actorId, previewId, "a".repeat(64),
                7, "v108-create-test-key", "b".repeat(64), "v108-submit-test-key", null);
    }

    private void submittedFixture() {
        when(actor.getOrganizationId()).thenReturn(org);
        when(actor.getId()).thenReturn(actorId);
        when(reads.find(caseId, org, actorId, true)).thenReturn(row("SUBMITTED", 1));
    }

    @Test
    void activeHoldReturnsConflictWithoutTransitionOrEvent() {
        submittedFixture();
        when(repository.hasActiveReviewHold(caseId, org)).thenReturn(true);
        assertThatThrownBy(() -> mutations.withdraw(caseId, 1, "v108-withdraw-test-key", actor))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Нельзя отозвать заявку до снятия юридического удержания.");
        var order = inOrder(reads, repository);
        order.verify(reads).find(caseId, org, actorId, true);
        order.verify(repository).hasActiveReviewHold(caseId, org);
        verify(repository, never()).transition(any(), any(), any(), any(), anyLong(), any());
        verify(repository, never()).event(any(), any(), any(), any(), anyLong());
    }

    @Test
    void releasedOrAbsentHoldPreservesNormalWithdrawal() {
        submittedFixture();
        when(repository.hasActiveReviewHold(caseId, org)).thenReturn(false);
        when(repository.transition(caseId, org, "SUBMITTED", "WITHDRAWN", 1, "v108-withdraw-test-key")).thenReturn(true);
        when(reads.find(caseId, org, actorId, false)).thenReturn(row("WITHDRAWN", 2));
        assertThat(mutations.withdraw(caseId, 1, "v108-withdraw-test-key", actor).state()).isEqualTo("WITHDRAWN");
        var order = inOrder(repository);
        order.verify(repository).hasActiveReviewHold(caseId, org);
        order.verify(repository).transition(caseId, org, "SUBMITTED", "WITHDRAWN", 1, "v108-withdraw-test-key");
        order.verify(repository).event(caseId, org, actorId, "WITHDRAWN", 2);
    }

    @Test
    void holdQueryIsTenantScopedAndUnknownFailsClosed() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var sqlRepository = new ErasureCaseRepository(jdbc);
        String sql = """
                SELECT EXISTS (
                    SELECT 1 FROM public.erasure_review_holds_v105
                    WHERE request_id = ? AND organization_id = ? AND released_at IS NULL
                )
                """;
        when(jdbc.queryForObject(sql, Boolean.class, caseId, org))
                .thenReturn(true).thenReturn(false).thenReturn(null);
        assertThat(sqlRepository.hasActiveReviewHold(caseId, org)).isTrue();
        assertThat(sqlRepository.hasActiveReviewHold(caseId, org)).isFalse();
        assertThat(sqlRepository.hasActiveReviewHold(caseId, org)).isTrue();
        verify(jdbc, times(3)).queryForObject(sql, Boolean.class, caseId, org);
        verifyNoMoreInteractions(jdbc);
    }
}
