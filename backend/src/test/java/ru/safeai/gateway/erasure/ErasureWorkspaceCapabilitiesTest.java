package ru.safeai.gateway.erasure;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ErasureWorkspaceCapabilitiesTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ErasureCaseAuthority authority = mock(ErasureCaseAuthority.class);
    private final ErasureReviewService reviews = mock(ErasureReviewService.class);
    private final SafeAiUserPrincipal actor = mock(SafeAiUserPrincipal.class);

    @Test
    void platformRoleOutsidePlatformDoesNotGrantRegistryOrReview() {
        when(authority.liveRoles(actor)).thenReturn(List.of("SUPER_ADMIN"));
        when(authority.platformSuperAdmin(actor)).thenReturn(false);
        var reads = new ErasureWorkspaceReads(jdbc, authority, reviews, true, true);
        var cap = reads.capabilities(actor);
        assertFalse(cap.platform());
        assertFalse(cap.tenantRegistry());
        assertFalse(cap.personalRequests());
        assertFalse(cap.reviewQueue());
        assertThrows(ForbiddenOperationException.class, () -> reads.registrySummary(actor));
        verifyNoInteractions(jdbc, reviews);
    }

    @Test
    void platformRoleWithEnabledApprovalsStillRequiresGrant() {
        when(authority.liveRoles(actor)).thenReturn(List.of("SUPER_ADMIN"));
        when(authority.platformSuperAdmin(actor)).thenReturn(true);
        when(reviews.access(actor)).thenReturn(new ErasureReviewTypes.ReviewAccess(false, false));
        var reads = new ErasureWorkspaceReads(jdbc, authority, reviews, true, true);
        assertTrue(reads.capabilities(actor).platform());
        assertFalse(reads.capabilities(actor).reviewQueue());
        assertThrows(ForbiddenOperationException.class,
                () -> reads.registry(actor, true, "ALL", "", 0));
        verifyNoInteractions(jdbc);
    }

    @Test
    void revokedReviewGrantIsNotCached() {
        when(authority.liveRoles(actor)).thenReturn(List.of("ADMIN"));
        when(reviews.access(actor)).thenReturn(new ErasureReviewTypes.ReviewAccess(true, false),
                new ErasureReviewTypes.ReviewAccess(false, false));
        var reads = new ErasureWorkspaceReads(jdbc, authority, reviews, true, true);
        assertTrue(reads.capabilities(actor).reviewQueue());
        assertFalse(reads.capabilities(actor).reviewQueue());
        verify(reviews, times(2)).access(actor);
        verifyNoInteractions(jdbc);
    }

    @Test
    void disabledFlagsDoNotGrantTenantIntakeOrReview() {
        when(authority.liveRoles(actor)).thenReturn(List.of("ADMIN"));
        var reads = new ErasureWorkspaceReads(jdbc, authority, reviews, false, false);
        var cap = reads.capabilities(actor);
        assertFalse(cap.tenantRegistry());
        assertFalse(cap.personalRequests());
        assertFalse(cap.reviewQueue());
        assertThrows(ForbiddenOperationException.class, () -> reads.registrySummary(actor));
        verifyNoInteractions(jdbc, reviews);
    }

    @Test
    void failedLiveAuthorityCheckStopsBeforeReviewOrRegistryRead() {
        when(authority.liveRoles(actor)).thenThrow(new ForbiddenOperationException("Недостаточно прав."));
        var reads = new ErasureWorkspaceReads(jdbc, authority, reviews, true, true);
        assertThrows(ForbiddenOperationException.class, () -> reads.capabilities(actor));
        assertThrows(ForbiddenOperationException.class, () -> reads.registrySummary(actor));
        verifyNoInteractions(jdbc, reviews);
    }
}
