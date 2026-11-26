package ru.safeai.gateway.erasure;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ErasureWorkspaceReadsTest {

    private final JdbcTemplate jdbc =
            mock(JdbcTemplate.class);

    private final ErasureCaseAuthority authority =
            mock(ErasureCaseAuthority.class);

    private final ErasureReviewService reviews =
            mock(ErasureReviewService.class);

    private final SafeAiUserPrincipal actor =
            mock(SafeAiUserPrincipal.class);

    @Test
    void platformDoesNotImplyReview() {

        when(authority.liveRoles(actor))
                .thenReturn(List.of("SUPER_ADMIN"));

        when(authority.platformSuperAdmin(actor))
                .thenReturn(true);

        var service = new ErasureWorkspaceReads(
                jdbc, authority, reviews, false, false
        );

        assertTrue(service.capabilities(actor).platform());
        assertFalse(service.capabilities(actor).personalRequests());

        assertThrows(
                ForbiddenOperationException.class,
                () -> service.registry(actor, true, "ALL", "", 0)
        );

        verifyNoInteractions(jdbc, reviews);
    }

    @Test
    void tenantNeedsSeparateReviewGrant() {

        when(authority.liveRoles(actor))
                .thenReturn(List.of("ADMIN"));

        when(reviews.access(actor))
                .thenReturn(
                        new ErasureReviewTypes.ReviewAccess(
                                false, false
                        )
                );

        var service = new ErasureWorkspaceReads(
                jdbc, authority, reviews, true, true
        );

        assertTrue(
                service.capabilities(actor).tenantRegistry()
        );

        assertThrows(
                ForbiddenOperationException.class,
                () -> service.registry(actor, true, "ALL", "", 0)
        );

        assertThrows(
                BadRequestException.class,
                () -> service.registry(actor, false, "INVALID", "", 0)
        );

        verifyNoInteractions(jdbc);
    }

    @Test
    void ordinaryUserCannotReadAdministrativeHistory() {

        when(authority.liveRoles(actor))
                .thenReturn(List.of("USER"));

        var service = new ErasureWorkspaceReads(
                jdbc, authority, reviews, true, true
        );

        assertTrue(
                service.capabilities(actor).personalRequests()
        );

        assertThrows(
                ForbiddenOperationException.class,
                () -> service.history(actor, "", 0)
        );

        assertThrows(
                ForbiddenOperationException.class,
                () -> service.registry(actor, false, "ALL", "", 0)
        );

        verifyNoInteractions(jdbc, reviews);
    }

    /**
     * Перехватывает SQL и параметры без подключения к PostgreSQL.
     * Сигнатура соответствует контракту JdbcTemplate.
     */
    private static final class CapturingJdbc extends JdbcTemplate {

        String sql = "";

        @Nullable Object @Nullable [] args;

        @Override
        @NullMarked
        public <T extends @Nullable Object> List<T> query(
                String sql,
                RowMapper<T> mapper,
                @Nullable Object @Nullable ... args
        ) {
            this.sql = sql;
            this.args = args;

            return List.of();
        }
    }

    @Test
    void reviewerScopeAndSearchAreBoundParameters() {

        var db = new CapturingJdbc();
        var org = UUID.randomUUID();

        when(authority.liveRoles(actor))
                .thenReturn(List.of("ADMIN"));

        when(actor.getOrganizationId())
                .thenReturn(org);

        when(reviews.access(actor))
                .thenReturn(
                        new ErasureReviewTypes.ReviewAccess(
                                true, false
                        )
                );

        var service = new ErasureWorkspaceReads(
                db, authority, reviews, true, true
        );

        String needle = "x' OR 1=1 --";

        var result = service.registry(
                actor, true, "PENDING", needle, 2
        );

        String sql = normalizeSql(db.sql);

        assertTrue(sql.contains("r.organization_id=?"));

        assertTrue(
                sql.contains("v.request_id IS NOT NULL")
        );

        assertFalse(sql.contains(needle));

        assertArrayEquals(
                new Object[]{
                        org,
                        "PENDING",
                        needle,
                        needle,
                        needle,
                        needle,
                        26,
                        50L
                },
                db.args
        );

        assertEquals(2, result.page());
        assertFalse(result.hasNext());
    }

    @Test
    void platformRegistryDoesNotJoinPersonalIdentity() {

        var db = new CapturingJdbc();

        when(authority.liveRoles(actor))
                .thenReturn(List.of("SUPER_ADMIN"));

        when(authority.platformSuperAdmin(actor))
                .thenReturn(true);

        var service = new ErasureWorkspaceReads(
                db, authority, reviews, false, false
        );

        service.registry(
                actor, false, "ATTENTION", "", 0
        );

        String sql = normalizeSql(db.sql);

        assertFalse(
                sql.contains("JOIN public.users")
        );

        assertTrue(
                sql.contains("CAST(NULL AS text) AS requester")
        );

        assertTrue(
                sql.contains(
                        "WHERE r.state IN ('SUBMITTED','WITHDRAWN')"
                )
        );

        assertArrayEquals(
                new Object[]{26, 0L},
                db.args
        );
    }

    @Test
    void tenantIntakeDoesNotReadIndependentReviewDecisions() {

        var db = new CapturingJdbc();
        var org = UUID.randomUUID();

        when(authority.liveRoles(actor))
                .thenReturn(List.of("ADMIN"));

        when(actor.getOrganizationId())
                .thenReturn(org);

        var service = new ErasureWorkspaceReads(
                db, authority, reviews, true, false
        );

        service.registry(
                actor, false, "DRAFT", "", 0
        );

        assertFalse(
                db.sql.contains("erasure_reviews_v105")
        );

        assertFalse(
                db.sql.contains("erasure_review_holds_v105")
        );

        assertArrayEquals(
                new Object[]{org, "DRAFT", 26, 0L},
                db.args
        );
    }

    /**
     * Исключает зависимость тестов от форматирования SQL.
     */
    private static String normalizeSql(String sql) {

        return sql
                .replaceAll("\\s+", " ")
                .replaceAll("\\s*=\\s*", "=")
                .replaceAll("\\s*,\\s*", ",")
                .trim();
    }
}
