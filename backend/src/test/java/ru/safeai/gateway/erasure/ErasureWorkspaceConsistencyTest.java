// SafeAI-Desk/backend/src/test/java/ru/safeai/gateway/erasure/ErasureWorkspaceConsistencyTest.java
package ru.safeai.gateway.erasure;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ErasureWorkspaceConsistencyTest {
    private final ErasureCaseAuthority authority = mock(ErasureCaseAuthority.class);
    private final ErasureReviewService reviews = mock(ErasureReviewService.class);
    private final SafeAiUserPrincipal actor = mock(SafeAiUserPrincipal.class);
    private final UUID organization = UUID.randomUUID();
    private final UUID request = UUID.randomUUID();

    private static final class CapturingJdbc extends JdbcTemplate {

        String sql = "";

        @Nullable Object @Nullable [] args;

        final ResultSet row = mock(ResultSet.class);

        boolean returnRow;

        @Override
        @NullMarked
        public <T extends @Nullable Object> List<T> query(
            String sql,
            RowMapper<T> mapper
        ) {
            return query(sql, mapper, new Object[0]);
        }

        @Override
        @NullMarked
        public <T extends @Nullable Object> List<T> query(
            String sql,
            RowMapper<T> mapper,
            @Nullable Object @Nullable ... args
        ) {
            this.sql = sql.replaceAll("\\s+", " ").trim();
            this.args = args;

            if (!returnRow) {
                return List.of();
            }

            try {
                return java.util.Collections.singletonList(
                    mapper.mapRow(row, 0)
                );
            } catch (SQLException e) {
                throw new AssertionError(
                    "Не удалось преобразовать результат SQL-запроса",
                    e
                );
            }
        }
    }

    private ErasureWorkspaceReads service(CapturingJdbc db, String role, boolean intake, boolean approvals) {
        when(authority.liveRoles(actor)).thenReturn(List.of(role));
        when(actor.getOrganizationId()).thenReturn(organization);
        if ("SUPER_ADMIN".equals(role)) when(authority.platformSuperAdmin(actor)).thenReturn(true);
        if (approvals) when(reviews.access(actor)).thenReturn(new ErasureReviewTypes.ReviewAccess(true, false));
        return new ErasureWorkspaceReads(db, authority, reviews, intake, approvals);
    }

    @Test
    void platformSummaryUsesRegisteredScopeAndCountsHeldCases()
        throws SQLException {

        var db = new CapturingJdbc();
        db.returnRow = true;

        when(db.row.getLong("total")).thenReturn(4L);
        when(db.row.getLong("registered")).thenReturn(3L);
        when(db.row.getLong("withdrawn")).thenReturn(1L);
        when(db.row.getLong("reviewed")).thenReturn(3L);
        when(db.row.getLong("pending")).thenReturn(2L);
        when(db.row.getLong("needs_information")).thenReturn(1L);
        when(db.row.getLong("held")).thenReturn(2L);

        var summary = service(
            db,
            "SUPER_ADMIN",
            false,
            false
        ).registrySummary(actor);

        assertAll(
            "Платформенная статистика",

            () -> assertEquals("PLATFORM", summary.scope()),
            () -> assertEquals(4L, summary.total()),
            () -> assertEquals(3L, summary.registered()),
            () -> assertEquals(1L, summary.withdrawn()),
            () -> assertEquals(3L, summary.reviewed()),
            () -> assertEquals(2L, summary.pending()),
            () -> assertEquals(1L, summary.needsInformation()),
            () -> assertEquals(2L, summary.held()),
            () -> assertNull(summary.drafts())
        );

        assertAll(
            "Безопасность платформенного SQL-запроса",

            () -> assertTrue(
                db.sql.contains(
                    "r.state IN ('SUBMITTED', 'WITHDRAWN')"
                )
            ),

            () -> assertTrue(
                db.sql.contains("count(*) FILTER (WHERE EXISTS")
            ),

            () -> assertFalse(
                db.sql.contains("JOIN public.users")
            ),

            () -> assertFalse(
                db.sql.contains("requested_by_user_id")
            ),

            () -> assertFalse(
                db.sql.contains("resource_id")
            ),

            () -> assertNotNull(db.args),

            () -> assertArrayEquals(
                new Object[0],
                db.args
            )
        );

        verify(authority, atLeastOnce()).liveRoles(actor);
        verify(authority, atLeastOnce()).platformSuperAdmin(actor);

        verifyNoInteractions(reviews);
    }

    @Test
    void tenantSummaryCannotReadReviewData() {
        var db = new CapturingJdbc();
        db.returnRow = true;
        var summary = service(db, "ADMIN", true, false).registrySummary(actor);
        assertEquals("TENANT", summary.scope());
        assertNull(summary.pending());
        assertNull(summary.reviewed());
        assertNull(summary.held());
        assertFalse(db.sql.contains("erasure_reviews_v105"));
        assertFalse(db.sql.contains("erasure_review_holds_v105"));
        assertArrayEquals(new Object[]{organization}, db.args);
    }

    @Test
    void directTenantCardIsScopedAndMissingOrForeignIsNotFound() {
        var db = new CapturingJdbc();
        var service = service(db, "ADMIN", true, false);
        assertThrows(ResourceNotFoundException.class, () -> service.registryDetail(actor, request));
        assertTrue(db.sql.contains("WHERE r.id = ? AND r.organization_id = ?"));
        assertFalse(db.sql.contains("erasure_reviews_v105"));
        assertFalse(db.sql.contains("erasure_review_holds_v105"));
        assertArrayEquals(new Object[]{request, organization}, db.args);
    }

    @Test
    void platformCardDoesNotReadDraftOrPersonalIdentity() {
        var db = new CapturingJdbc();
        var service = service(db, "SUPER_ADMIN", false, false);
        assertThrows(ResourceNotFoundException.class, () -> service.registryDetail(actor, request));
        assertTrue(db.sql.contains("r.state IN ('SUBMITTED', 'WITHDRAWN')"));
        assertFalse(db.sql.contains("requested_by_user_id"));
        assertFalse(db.sql.contains("JOIN public.users"));
        assertFalse(db.sql.contains("resource_id"));
        assertArrayEquals(new Object[]{request}, db.args);
    }

    @Test
    void reviewerHistoryJoinsBothJournalsOnlyForReviewedTenantCases() {
        var db = new CapturingJdbc();
        String needle = "x' OR 1=1 --";
        var page = service(db, "ADMIN", true, true).history(actor, needle, 2);
        assertTrue(db.sql.contains("public.erasure_request_events_v103"));
        assertTrue(db.sql.contains("public.erasure_review_events_v105"));
        assertTrue(db.sql.contains("r.organization_id = ? AND EXISTS"));
        assertTrue(db.sql.contains("v.organization_id = e.organization_id"));
        assertFalse(db.sql.contains(needle));
        assertArrayEquals(new Object[]{organization, needle, 26, 50L}, db.args);
        assertEquals(2, page.page());
    }

    @Test
    void userCannotReadSummaryOrCard() {
        var db = new CapturingJdbc();
        var service = service(db, "USER", true, false);
        assertThrows(ForbiddenOperationException.class, () -> service.registrySummary(actor));
        assertThrows(ForbiddenOperationException.class, () -> service.registryDetail(actor, request));
        assertEquals("", db.sql);
    }
}
