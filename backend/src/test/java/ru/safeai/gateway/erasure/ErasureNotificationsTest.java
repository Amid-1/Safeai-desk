package ru.safeai.gateway.erasure;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ErasureNotificationsTest {
    private final CapturingJdbc jdbc = new CapturingJdbc();
    private final ErasureWorkspaceReads workspace = mock(ErasureWorkspaceReads.class);
    private final ErasureReviewService reviews = mock(ErasureReviewService.class);
    private final SafeAiUserPrincipal actor = mock(SafeAiUserPrincipal.class);
    private final ErasureNotifications notifications = new ErasureNotifications(jdbc, workspace, reviews);
    private final UUID tenant = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();

    /** Captures SQL without executing it or accessing a DataSource. */
    @NullMarked
    private static final class CapturingJdbc extends JdbcTemplate {
        private final List<SqlCall> calls = new ArrayList<>();
        private @Nullable Long count = 0L;

        @Override
        public <T> @Nullable T queryForObject(String sql, Class<T> requiredType) {
            calls.add(new SqlCall(sql, List.of(), true));
            assertEquals(Long.class, requiredType);
            return count == null ? null : requiredType.cast(count);
        }

        @Override
        public <T> @Nullable T queryForObject(String sql, Class<T> requiredType,
                @Nullable Object @Nullable ... args) {
            calls.add(new SqlCall(sql, boundArgs(args), true));
            assertEquals(Long.class, requiredType);
            return count == null ? null : requiredType.cast(count);
        }

        @Override
        public <T extends @Nullable Object> List<T> query(String sql, RowMapper<T> mapper,
                @Nullable Object @Nullable ... args) {
            calls.add(new SqlCall(sql, boundArgs(args), false));
            assertNotNull(mapper);
            return List.of();
        }

        private static List<Object> boundArgs(@Nullable Object @Nullable [] args) {
            assertNotNull(args, "Expected explicit SQL arguments");
            List<Object> values = new ArrayList<>();
            for (var arg : args) {
                assertNotNull(arg, "Unexpected null SQL argument");
                values.add(arg);
            }
            return List.copyOf(values);
        }
    }

    private record SqlCall(String sql, List<Object> args, boolean counter) { }

    private SqlCall onlyCall(boolean counter) {
        assertEquals(1, jdbc.calls.size());
        var call = jdbc.calls.getFirst();
        assertEquals(counter, call.counter());
        return call;
    }

    @Test
    void deniedActorNeverReadsNotifications() {
        when(workspace.capabilities(actor)).thenThrow(new ForbiddenOperationException("Denied"));
        assertThrows(ForbiddenOperationException.class, () -> notifications.feed(actor));
        assertTrue(jdbc.calls.isEmpty());
        verifyNoInteractions(reviews);
    }

    @Test
    void disabledFlagsAndOrdinaryRoleNeverReadEvents() {
        when(workspace.capabilities(actor)).thenReturn(new ErasureWorkspaceReads.Capabilities(false, false, false, false, false, false));
        var feed = notifications.feed(actor);
        assertFalse(feed.canRequest());
        assertNull(feed.registryCount());
        assertNull(feed.reviewCount());
        assertTrue(feed.personal().isEmpty());
        assertTrue(feed.review().isEmpty());
        assertTrue(jdbc.calls.isEmpty());
        verifyNoInteractions(reviews);
    }

    @Test
    void platformCounterDoesNotExposePersonalIdentityOrGrantReview() {
        when(workspace.capabilities(actor)).thenReturn(new ErasureWorkspaceReads.Capabilities(true, false, false, false, false, false));
        jdbc.count = 3L;
        var feed = notifications.feed(actor);
        assertEquals(3L, feed.registryCount());
        assertNull(feed.reviewCount());
        assertTrue(feed.personal().isEmpty());
        assertTrue(feed.review().isEmpty());
        var call = onlyCall(true);
        assertTrue(call.sql().contains("r.state = 'SUBMITTED'"));
        assertTrue(call.sql().contains("v.review_state = 'PENDING'"));
        assertTrue(call.sql().contains("v.organization_id = r.organization_id"));
        assertFalse(call.sql().contains("public.users"));
        assertFalse(call.sql().contains("requester_user_id"));
        assertTrue(call.args().isEmpty());
        verifyNoInteractions(reviews);
    }

    @Test
    void requesterOnlyQueriesOwnTenantAndOwnIdentity() {
        when(workspace.capabilities(actor)).thenReturn(new ErasureWorkspaceReads.Capabilities(false, false, true, false, true, true));
        when(actor.getOrganizationId()).thenReturn(tenant);
        when(actor.getId()).thenReturn(user);
        var feed = notifications.feed(actor);
        assertTrue(feed.canRequest());
        assertNull(feed.registryCount());
        assertNull(feed.reviewCount());
        assertTrue(feed.review().isEmpty());
        var call = onlyCall(false);
        assertTrue(call.sql().contains("v.organization_id = ? AND v.requester_user_id = ?"));
        assertTrue(call.sql().contains("v.organization_id = e.organization_id"));
        assertTrue(call.sql().contains("'APPROVED_REVIEW_ONLY','REJECTED','NEEDS_INFORMATION'"));
        assertTrue(call.sql().contains("LIMIT 20"));
        assertEquals(List.of(tenant, user), call.args());
        assertFalse(call.sql().contains(tenant.toString()));
        assertFalse(call.sql().contains(user.toString()));
        verifyNoInteractions(reviews);
    }

    @Test
    void legalOnlyDoesNotReceiveReviewerNotifications() {
        when(workspace.capabilities(actor)).thenReturn(new ErasureWorkspaceReads.Capabilities(false, false, false, true, false, true));
        when(reviews.access(actor)).thenReturn(new ErasureReviewTypes.ReviewAccess(false, true));
        var feed = notifications.feed(actor);
        assertNull(feed.reviewCount());
        assertEquals(List.of(), feed.review());
        assertTrue(jdbc.calls.isEmpty());
        verify(reviews).access(actor);
    }

    @Test
    void reviewCounterAndEventsAreBoundToTenantAndExcludeSelf() {
        when(workspace.capabilities(actor)).thenReturn(new ErasureWorkspaceReads.Capabilities(false, false, false, true, false, true));
        when(reviews.access(actor)).thenReturn(new ErasureReviewTypes.ReviewAccess(true, false));
        when(actor.getOrganizationId()).thenReturn(tenant);
        when(actor.getId()).thenReturn(user);
        jdbc.count = 2L;
        var feed = notifications.feed(actor);
        assertEquals(2L, feed.reviewCount());
        assertNull(feed.registryCount());
        assertTrue(feed.personal().isEmpty());
        assertEquals(2, jdbc.calls.size());
        var counter = jdbc.calls.get(0);
        var events = jdbc.calls.get(1);
        assertTrue(counter.counter());
        assertFalse(events.counter());
        for (var call : jdbc.calls) {
            assertTrue(call.sql().contains("v.organization_id = ? AND v.requester_user_id <> ?"));
            assertTrue(call.sql().contains("v.review_state = 'PENDING'"));
            assertTrue(call.sql().contains("a.organization_id = v.organization_id AND a.user_id = ?"));
            assertTrue(call.sql().contains("a.authority = 'REVIEW' AND a.enabled"));
            assertEquals(List.of(tenant, user, user), call.args());
            assertFalse(call.sql().contains(tenant.toString()));
            assertFalse(call.sql().contains(user.toString()));
        }
        assertTrue(events.sql().contains("'SUBMITTED','INFORMATION_PROVIDED'"));
        assertTrue(events.sql().contains("e.review_version = v.review_version"));
        assertTrue(events.sql().contains("v.organization_id = e.organization_id"));
        assertTrue(events.sql().contains("LIMIT 20"));
        verify(reviews).access(actor);
    }

    @Test
    void tenantCounterDoesNotReadIndependentReviewDecisions() {
        when(workspace.capabilities(actor)).thenReturn(new ErasureWorkspaceReads.Capabilities(false, true, true, false, true, false));
        when(actor.getOrganizationId()).thenReturn(tenant);
        jdbc.count = 4L;
        var feed = notifications.feed(actor);
        assertEquals(4L, feed.registryCount());
        assertNull(feed.reviewCount());
        assertTrue(feed.personal().isEmpty());
        assertTrue(feed.review().isEmpty());
        var call = onlyCall(true);
        assertTrue(call.sql().contains("r.organization_id = ? AND r.state = 'SUBMITTED'"));
        assertFalse(call.sql().contains("erasure_reviews_v105"));
        assertFalse(call.sql().contains("erasure_review_events_v105"));
        assertEquals(List.of(tenant), call.args());
        verifyNoInteractions(reviews);
    }

    @Test
    void revokedReviewerGrantPreventsReadsEvenWhenCapabilitiesPreviouslyAllowedQueue() {
        when(workspace.capabilities(actor)).thenReturn(new ErasureWorkspaceReads.Capabilities(false, false, false, true, false, true));
        when(reviews.access(actor)).thenReturn(new ErasureReviewTypes.ReviewAccess(true, false), new ErasureReviewTypes.ReviewAccess(false, false));
        when(actor.getOrganizationId()).thenReturn(tenant);
        when(actor.getId()).thenReturn(user);
        jdbc.count = 2L;
        assertEquals(2L, notifications.feed(actor).reviewCount());
        assertEquals(2, jdbc.calls.size());
        jdbc.calls.clear();
        var feed = notifications.feed(actor);
        assertNull(feed.reviewCount());
        assertTrue(feed.review().isEmpty());
        assertTrue(jdbc.calls.isEmpty());
        verify(reviews, times(2)).access(actor);
    }

    @Test
    void requesterWithDisabledApprovalsDoesNotReadDecisionEvents() {
        when(workspace.capabilities(actor)).thenReturn(new ErasureWorkspaceReads.Capabilities(false, false, true, false, true, false));
        var feed = notifications.feed(actor);
        assertTrue(feed.canRequest());
        assertTrue(feed.personal().isEmpty());
        assertTrue(feed.review().isEmpty());
        assertNull(feed.registryCount());
        assertNull(feed.reviewCount());
        assertTrue(jdbc.calls.isEmpty());
        verifyNoInteractions(reviews);
    }

    @Test
    void nullablePlatformCounterIsNotReportedAsZero() {
        when(workspace.capabilities(actor)).thenReturn(
                new ErasureWorkspaceReads.Capabilities(true, false, false, false, false, false));
        jdbc.count = null;

        var feed = notifications.feed(actor);

        assertNull(feed.registryCount());
        assertNull(feed.reviewCount());
        assertTrue(feed.personal().isEmpty());
        assertTrue(feed.review().isEmpty());
        var call = onlyCall(true);
        assertTrue(call.sql().contains("r.state = 'SUBMITTED'"));
        assertFalse(call.sql().contains("public.users"));
        assertTrue(call.args().isEmpty());
        verifyNoInteractions(reviews);
    }

    @Test
    void nullableTenantCounterIsNotReportedAsZero() {
        when(workspace.capabilities(actor)).thenReturn(
                new ErasureWorkspaceReads.Capabilities(false, true, true, false, true, false));
        when(actor.getOrganizationId()).thenReturn(tenant);
        jdbc.count = null;

        var feed = notifications.feed(actor);

        assertTrue(feed.canRequest());
        assertNull(feed.registryCount());
        assertNull(feed.reviewCount());
        assertTrue(feed.personal().isEmpty());
        assertTrue(feed.review().isEmpty());
        var call = onlyCall(true);
        assertTrue(call.sql().contains("r.organization_id = ? AND r.state = 'SUBMITTED'"));
        assertFalse(call.sql().contains("erasure_reviews_v105"));
        assertEquals(List.of(tenant), call.args());
        verifyNoInteractions(reviews);
    }
}
