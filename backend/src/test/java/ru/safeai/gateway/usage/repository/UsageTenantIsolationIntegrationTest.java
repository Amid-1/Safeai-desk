package ru.safeai.gateway.usage.repository;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Slice;
import org.springframework.test.context.ActiveProfiles;
import ru.safeai.gateway.usage.dto.UsageSummaryResponse;
import ru.safeai.gateway.usage.testsupport.UsagePostgresIntegrationTestSupport;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
@SpringBootTest(properties = "safeai.usage.rollup.enabled=false")
@ActiveProfiles("test")
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class UsageTenantIsolationIntegrationTest
        extends UsagePostgresIntegrationTestSupport {

    private static final String MODEL = "tenant-model";

    @Autowired
    private UsageQueryRepository repository;

    @Test
    void organizationScopedReportContainsOnlyOwnTenant() {
        insertTenantUsage();

        Slice<UsageSummaryResponse> organizationA =
                repository.findSummary(
                        criteria(
                                ORGANIZATION_A_ID,
                                null,
                                null,
                                RANGE_FROM,
                                RANGE_TO
                        ),
                        livePlan(RANGE_FROM, RANGE_TO),
                        PAGEABLE
                );

        assertThat(organizationA.getContent())
                .extracting(UsageSummaryResponse::userId)
                .containsExactly(USER_A_ID);
        assertThat(organizationA.getContent())
                .extracting(UsageSummaryResponse::currentUserEmail)
                .containsExactly("usage-a@test.com");
    }

    @Test
    void foreignUserIdCannotBypassOrganizationScope() {
        insertTenantUsage();

        Slice<UsageSummaryResponse> result =
                repository.findSummary(
                        criteria(
                                ORGANIZATION_A_ID,
                                USER_B_ID,
                                null,
                                RANGE_FROM,
                                RANGE_TO
                        ),
                        livePlan(RANGE_FROM, RANGE_TO),
                        PAGEABLE
                );

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    void globalReportContainsAllTenantsForSuperAdminPath() {
        insertTenantUsage();

        Slice<UsageSummaryResponse> global =
                repository.findSummary(
                        criteria(
                                null,
                                null,
                                null,
                                RANGE_FROM,
                                RANGE_TO
                        ),
                        livePlan(RANGE_FROM, RANGE_TO),
                        PAGEABLE
                );

        assertThat(global.getContent())
                .extracting(UsageSummaryResponse::userId)
                .containsExactly(USER_A_ID, USER_B_ID);
    }

    @Test
    void databaseRejectsCrossTenantChatSession() {
        UUID invalidSessionId = UUID.randomUUID();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        insert into public.chat_sessions (
                            id,
                            user_id,
                            organization_id,
                            title,
                            created_at,
                            updated_at,
                            version
                        ) values (?, ?, ?, ?, ?, ?, 0)
                        """,
                invalidSessionId,
                USER_A_ID,
                ORGANIZATION_B_ID,
                "Cross tenant session",
                Timestamp.from(RANGE_FROM),
                Timestamp.from(RANGE_FROM)
        ))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_chat_sessions_user_organization");
    }

    @Test
    void databaseRejectsCrossTenantChatMessage() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                        insert into public.chat_messages (
                            id,
                            session_id,
                            organization_id,
                            role,
                            content,
                            status,
                            created_at,
                            usage_status,
                            pricing_status
                        ) values (
                            ?, ?, ?,
                            'ASSISTANT',
                            'Cross tenant message',
                            'FAILED',
                            ?,
                            'NOT_APPLICABLE',
                            'NOT_APPLICABLE'
                        )
                        """,
                UUID.randomUUID(),
                SESSION_A_ID,
                ORGANIZATION_B_ID,
                Timestamp.from(RANGE_FROM.plusSeconds(1))
        ))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "fk_chat_messages_session_organization"
                );
    }

    @Test
    void databaseRejectsCrossTenantUserRollup() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                        insert into public.usage_daily_user_model_rollups (
                            usage_date,
                            organization_id,
                            user_id,
                            model,
                            created_at,
                            updated_at
                        ) values (?, ?, ?, ?, current_timestamp, current_timestamp)
                        """,
                Date.valueOf(LocalDate.of(2026, 6, 10)),
                ORGANIZATION_B_ID,
                USER_A_ID,
                MODEL
        ))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "fk_usage_daily_user_model_user_organization"
                );
    }

    private void insertTenantUsage() {
        Instant createdAt = Instant.parse("2026-06-10T12:00:00Z");

        insertCompletedAssistant(
                SESSION_A_ID,
                ORGANIZATION_A_ID,
                MODEL,
                "COMPLETED",
                10,
                20,
                "AVAILABLE",
                new BigDecimal("0.010000000000"),
                "PRICED",
                createdAt
        );

        insertCompletedAssistant(
                SESSION_B_ID,
                ORGANIZATION_B_ID,
                MODEL,
                "COMPLETED",
                30,
                40,
                "AVAILABLE",
                new BigDecimal("0.020000000000"),
                "PRICED",
                createdAt.plusSeconds(1)
        );
    }
}
