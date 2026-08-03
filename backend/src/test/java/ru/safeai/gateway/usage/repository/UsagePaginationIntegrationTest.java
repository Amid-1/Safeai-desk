package ru.safeai.gateway.usage.repository;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.test.context.ActiveProfiles;
import ru.safeai.gateway.usage.dto.UsageSummaryResponse;
import ru.safeai.gateway.usage.testsupport.UsagePostgresIntegrationTestSupport;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@SpringBootTest(properties = "safeai.usage.rollup.enabled=false")
@ActiveProfiles("test")
class UsagePaginationIntegrationTest
        extends UsagePostgresIntegrationTestSupport {

    private static final UUID SECOND_USER_ID =
            UUID.fromString(
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb3"
            );
    private static final UUID SECOND_SESSION_ID =
            UUID.fromString(
                    "cccccccc-cccc-cccc-cccc-ccccccccccc3"
            );
    private static final String MODEL = "paging-model";

    @Autowired
    private UsageQueryRepository repository;

    @Test
    void immutableUserIdOrderingPreventsEmailChangesFromMovingPages() {
        insertUser(
                SECOND_USER_ID,
                ORGANIZATION_A_ID,
                "aaa-before-change@test.com",
                true,
                "USER",
                RANGE_FROM
        );
        insertChatSession(
                SECOND_SESSION_ID,
                SECOND_USER_ID,
                ORGANIZATION_A_ID
        );

        insertUsage(
                SESSION_A_ID,
                USER_A_ID,
                Instant.parse("2026-06-10T10:00:00Z")
        );
        insertUsage(
                SECOND_SESSION_ID,
                SECOND_USER_ID,
                Instant.parse("2026-06-10T11:00:00Z")
        );

        UsageQueryCriteria criteria = criteria(
                ORGANIZATION_A_ID,
                null,
                null,
                RANGE_FROM,
                RANGE_TO
        );
        UsageQueryPlan plan = livePlan(RANGE_FROM, RANGE_TO);

        Slice<UsageSummaryResponse> firstPage =
                repository.findSummary(
                        criteria,
                        plan,
                        PageRequest.of(0, 1)
                );

        assertThat(firstPage.getContent())
                .extracting(UsageSummaryResponse::userId)
                .containsExactly(USER_A_ID);
        assertThat(firstPage.hasNext()).isTrue();

        jdbcTemplate.update(
                "update public.users set email = ? where id = ?",
                "zzz-after-change@test.com",
                USER_A_ID
        );

        Slice<UsageSummaryResponse> secondPage =
                repository.findSummary(
                        criteria,
                        plan,
                        PageRequest.of(1, 1)
                );

        assertThat(secondPage.getContent())
                .extracting(UsageSummaryResponse::userId)
                .containsExactly(SECOND_USER_ID);
        assertThat(secondPage.getContent())
                .extracting(UsageSummaryResponse::currentUserEmail)
                .containsExactly("aaa-before-change@test.com");
    }

    @Test
    void historicalRowsReturnCurrentEmailAsDisplayValue() {
        insertUsage(
                SESSION_A_ID,
                USER_A_ID,
                Instant.parse("2026-06-10T10:00:00Z")
        );

        jdbcTemplate.update(
                "update public.users set email = ? where id = ?",
                "current-email@test.com",
                USER_A_ID
        );

        Slice<UsageSummaryResponse> result =
                repository.findSummary(
                        criteria(
                                ORGANIZATION_A_ID,
                                USER_A_ID,
                                null,
                                RANGE_FROM,
                                RANGE_TO
                        ),
                        livePlan(RANGE_FROM, RANGE_TO),
                        PageRequest.of(0, 50)
                );

        assertThat(result.getContent())
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.userId()).isEqualTo(USER_A_ID);
                    assertThat(row.currentUserEmail())
                            .isEqualTo("current-email@test.com");
                });
    }

    @Test
    void pageSizePlusOneImplementsSliceHasNextWithoutCountQuery() {
        insertUser(
                SECOND_USER_ID,
                ORGANIZATION_A_ID,
                "second@test.com",
                true,
                "USER",
                RANGE_FROM
        );
        insertChatSession(
                SECOND_SESSION_ID,
                SECOND_USER_ID,
                ORGANIZATION_A_ID
        );

        insertUsage(
                SESSION_A_ID,
                USER_A_ID,
                Instant.parse("2026-06-10T10:00:00Z")
        );
        insertUsage(
                SECOND_SESSION_ID,
                SECOND_USER_ID,
                Instant.parse("2026-06-10T11:00:00Z")
        );

        Slice<UsageSummaryResponse> result =
                repository.findSummary(
                        criteria(
                                ORGANIZATION_A_ID,
                                null,
                                null,
                                RANGE_FROM,
                                RANGE_TO
                        ),
                        livePlan(RANGE_FROM, RANGE_TO),
                        PageRequest.of(0, 1)
                );

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.hasNext()).isTrue();
        assertThat(result.getNumber()).isZero();
        assertThat(result.getSize()).isEqualTo(1);
    }

    private void insertUsage(
            UUID sessionId,
            UUID userId,
            Instant createdAt
    ) {
        insertCompletedAssistant(
                sessionId,
                ORGANIZATION_A_ID,
                MODEL + "-" + userId.toString().substring(0, 8),
                "COMPLETED",
                10,
                20,
                "AVAILABLE",
                BigDecimal.ZERO,
                "FREE",
                createdAt
        );
    }
}
