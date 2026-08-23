package ru.safeai.gateway.user.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import ru.safeai.gateway.audit.service.AuditOutboxScheduler;
import ru.safeai.gateway.common.exception.ConflictException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.exception.ResourceNotFoundException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.testsupport.AbstractPostgresIntegrationTest;
import ru.safeai.gateway.user.dto.CreateUserRequest;
import ru.safeai.gateway.user.dto.PermanentDeleteUserRequest;
import ru.safeai.gateway.user.dto.UpdateUserEnabledRequest;
import ru.safeai.gateway.user.dto.UpdateUserRequest;
import ru.safeai.gateway.user.dto.UpdateUserRolesRequest;
import ru.safeai.gateway.user.dto.UserResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SuppressWarnings({
        "SqlResolve",
        "SqlNoDataSourceInspection"
})
@SpringBootTest
@ActiveProfiles("test")
class UserManagementPostgresIntegrationTest
        extends AbstractPostgresIntegrationTest {

    private static final UUID ORGANIZATION_A_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID ORGANIZATION_B_ID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID ADMIN_ID =
            UUID.fromString("11111111-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID SUPER_ADMIN_ID =
            UUID.fromString("22222222-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final Instant CREATED_AT =
            Instant.parse("2026-07-20T10:00:00Z");
    private static final String VALID_PASSWORD =
            "Strong_User_123!";

    @Autowired
    private UserService userService;

    @MockitoBean
    private AuditOutboxScheduler auditOutboxScheduler;

    @Test
    void adminOrgACannotSeeOrChangeUserOrgB() {
        insertOrganization(ORGANIZATION_A_ID, "Organization A", true);
        insertOrganization(ORGANIZATION_B_ID, "Organization B", true);

        UUID targetUserId = UUID.randomUUID();
        insertUser(
                targetUserId,
                ORGANIZATION_B_ID,
                "user-b@test.com",
                true,
                "USER",
                CREATED_AT
        );

        assertThatThrownBy(() -> userService.findDetailsById(
                targetUserId,
                adminPrincipal()
        )).isInstanceOf(ResourceNotFoundException.class);

        assertThatThrownBy(() -> userService.updateUser(
                targetUserId,
                new UpdateUserRequest(
                        "changed-b@test.com",
                        "Changed User B",
                        0L
                ),
                adminPrincipal()
        )).isInstanceOf(ResourceNotFoundException.class);

        assertThat(jdbcTemplate.queryForObject("""
                select email
                from public.users
                where id = ?
                """, String.class, targetUserId))
                .isEqualTo("user-b@test.com");
    }

    @Test
    void organizationIdSubstitutionOnCreateIsBlocked() {
        insertOrganization(ORGANIZATION_A_ID, "Organization A", true);
        insertOrganization(ORGANIZATION_B_ID, "Organization B", true);

        assertThatThrownBy(() -> userService.create(
                new CreateUserRequest(
                        ORGANIZATION_B_ID,
                        "injected@test.com",
                        VALID_PASSWORD,
                        "Injected User",
                        Set.of("USER")
                ),
                adminPrincipal()
        ))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("другой организации");

        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                from public.users
                where email = 'injected@test.com'
                """, Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void adminCreatesUserWithUserRoleAndDatabaseTimestamps() {
        insertOrganization(ORGANIZATION_A_ID, "Organization A", true);

        UserResponse response = userService.create(
                new CreateUserRequest(
                        ORGANIZATION_A_ID,
                        " New.User@Test.com ",
                        VALID_PASSWORD,
                        " New   User ",
                        Set.of("USER")
                ),
                adminPrincipal()
        );

        assertThat(response.email()).isEqualTo("new.user@test.com");
        assertThat(response.fullName()).isEqualTo("New User");
        assertThat(response.roles()).containsExactly("USER");
        assertThat(response.createdAt()).isNotNull();
        assertThat(response.updatedAt()).isNotNull();
    }

    @Test
    void concurrentDuplicateEmailCreationProducesOneSuccessAndOneConflict()
            throws Exception {
        insertOrganization(ORGANIZATION_A_ID, "Organization A", true);

        List<String> outcomes = runConcurrently(
                () -> createUserOutcome(
                        " race.user@test.com "
                ),
                () -> createUserOutcome(
                        "RACE.USER@TEST.COM"
                )
        );

        assertThat(outcomes)
                .containsExactlyInAnyOrder(
                        "SUCCESS",
                        "CONFLICT"
                );

        Integer count = jdbcTemplate.queryForObject(
                """
                select count(*)
                from public.users
                where email = 'race.user@test.com'
                """,
                Integer.class
        );

        assertThat(count).isEqualTo(1);
    }

    @Test
    void updateResponseUsesDatabaseUpdatedAtAndIncrementsVersion() {
        insertOrganization(ORGANIZATION_A_ID, "Organization A", true);

        UserResponse created = userService.create(
                new CreateUserRequest(
                        ORGANIZATION_A_ID,
                        "timestamp-user@test.com",
                        VALID_PASSWORD,
                        "Before",
                        Set.of("USER")
                ),
                adminPrincipal()
        );

        UserResponse updated = userService.updateUser(
                created.id(),
                new UpdateUserRequest(
                        created.email(),
                        "After",
                        created.version()
                ),
                adminPrincipal()
        );

        assertThat(updated.version())
                .isEqualTo(created.version() + 1L);
        assertThat(updated.updatedAt())
                .isAfterOrEqualTo(created.updatedAt());
    }

    @Test
    void equalCreatedAtUsesIdTieBreakerWithoutPageDuplicates() {
        insertOrganization(ORGANIZATION_A_ID, "Organization A", true);

        List<UUID> inserted = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            UUID userId = UUID.randomUUID();
            inserted.add(userId);
            insertUser(
                    userId,
                    ORGANIZATION_A_ID,
                    "page-" + index + "@test.com",
                    true,
                    "USER",
                    CREATED_AT
            );
        }

        Set<UUID> received = new HashSet<>();

        for (int page = 0; page < 3; page++) {
            Page<UserResponse> result = userService.findAll(
                    superAdminPrincipal(),
                    null,
                    PageRequest.of(
                            page,
                            2,
                            Sort.by(Sort.Order.desc("createdAt"))
                    )
            );

            for (UserResponse user : result.getContent()) {
                assertThat(received.add(user.id()))
                        .as("user must not repeat between pages")
                        .isTrue();
            }
        }

        assertThat(received).containsExactlyInAnyOrderElementsOf(inserted);
    }

    @Test
    void lastActiveAdminCannotBePermanentlyDeleted() {
        insertOrganization(ORGANIZATION_A_ID, "Organization A", true);
        UUID adminId = UUID.randomUUID();
        insertUser(
                adminId,
                ORGANIZATION_A_ID,
                "last-admin@test.com",
                true,
                "ADMIN",
                CREATED_AT
        );

        assertThatThrownBy(() -> userService.permanentlyDelete(
                adminId,
                new PermanentDeleteUserRequest("last-admin@test.com", 0L),
                superAdminPrincipal()
        ))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("последнего активного администратора");

        assertThat(userExists(adminId)).isTrue();
    }

    @Test
    void twoConcurrentDisablesLeaveOneActiveAdmin() throws Exception {
        insertOrganization(ORGANIZATION_A_ID, "Organization A", true);
        UUID firstAdminId = UUID.randomUUID();
        UUID secondAdminId = UUID.randomUUID();
        insertUser(
                firstAdminId,
                ORGANIZATION_A_ID,
                "admin-1@test.com",
                true,
                "ADMIN",
                CREATED_AT
        );
        insertUser(
                secondAdminId,
                ORGANIZATION_A_ID,
                "admin-2@test.com",
                true,
                "ADMIN",
                CREATED_AT
        );

        List<String> outcomes = runConcurrently(
                () -> disableOutcome(firstAdminId),
                () -> disableOutcome(secondAdminId)
        );

        assertThat(outcomes)
                .containsExactlyInAnyOrder("SUCCESS", "FORBIDDEN");
        assertThat(activeAdminCount(ORGANIZATION_A_ID)).isEqualTo(1L);
    }

    @Test
    void concurrentDisableAndRoleRemovalLeaveOneActiveAdmin()
            throws Exception {
        insertOrganization(ORGANIZATION_A_ID, "Organization A", true);
        UUID firstAdminId = UUID.randomUUID();
        UUID secondAdminId = UUID.randomUUID();
        insertUser(
                firstAdminId,
                ORGANIZATION_A_ID,
                "admin-disable@test.com",
                true,
                "ADMIN",
                CREATED_AT
        );
        insertUser(
                secondAdminId,
                ORGANIZATION_A_ID,
                "admin-role@test.com",
                true,
                "ADMIN",
                CREATED_AT
        );

        List<String> outcomes = runConcurrently(
                () -> disableOutcome(firstAdminId),
                () -> roleRemovalOutcome(secondAdminId)
        );

        assertThat(outcomes)
                .containsExactlyInAnyOrder("SUCCESS", "FORBIDDEN");
        assertThat(activeAdminCount(ORGANIZATION_A_ID)).isEqualTo(1L);
    }

    @Test
    void activeRefreshTokenBlocksPermanentDeletion() {
        DeletionFixture fixture = disabledUserFixture("refresh@test.com");
        insertActiveRefreshToken(fixture.userId(), 0L);

        assertDeletionConflict(fixture, "refresh-сессии");
    }

    @Test
    void chatHistoryBlocksPermanentDeletion() {
        DeletionFixture fixture = disabledUserFixture("chat@test.com");
        insertChatSession(
                UUID.randomUUID(),
                fixture.userId(),
                fixture.organizationId()
        );

        assertDeletionConflict(fixture, "связанные данные");
    }

    @Test
    void usageHistoryBlocksPermanentDeletion() {
        DeletionFixture fixture = disabledUserFixture("usage@test.com");
        insertConsistentUsageRollup(
                fixture.userId(),
                fixture.organizationId()
        );

        assertDeletionConflict(fixture, "связанные данные");
    }

    @Test
    void quotaDependencyBlocksPermanentDeletion() {
        DeletionFixture fixture = disabledUserFixture("quota@test.com");
        insertUserQuota(fixture.userId());

        assertDeletionConflict(fixture, "связанные данные");
    }

    @Test
    void auditDependencyBlocksPermanentDeletion() {
        DeletionFixture fixture = disabledUserFixture("audit@test.com");
        insertAuditDependency(
                fixture.userId(),
                fixture.organizationId(),
                fixture.email()
        );

        assertDeletionConflict(fixture, "связанные данные");
    }

    @Test
    void pendingAuditOutboxDependencyBlocksPermanentDeletion() {
        DeletionFixture fixture = disabledUserFixture(
                "audit-outbox@test.com"
        );
        insertAuditOutboxDependency(
                fixture.userId(),
                fixture.organizationId(),
                fixture.email()
        );

        assertDeletionConflict(fixture, "связанные данные");
    }

    @Test
    void emptyDisabledUserCanBePermanentlyDeleted() {
        DeletionFixture fixture = disabledUserFixture("empty@test.com");

        userService.permanentlyDelete(
                fixture.userId(),
                new PermanentDeleteUserRequest(fixture.email(), 0L),
                superAdminPrincipal()
        );

        assertThat(userExists(fixture.userId())).isFalse();
    }

    @Test
    void concurrentChatCreationCannotProduceOrphanRow()
            throws Exception {
        DeletionFixture fixture = disabledUserFixture(
                "concurrent-chat@test.com"
        );
        UUID chatId = UUID.randomUUID();

        List<String> outcomes = runConcurrently(
                () -> deletionOutcome(fixture),
                () -> chatCreationOutcome(fixture, chatId)
        );

        assertThat(outcomes).containsAnyOf(
                "DELETE_SUCCESS",
                "CHAT_SUCCESS"
        );
        assertThat(
                outcomes.contains("DELETE_SUCCESS")
                        && outcomes.contains("CHAT_SUCCESS")
        ).isFalse();

        Integer orphanCount = jdbcTemplate.queryForObject("""
                select count(*)
                from public.chat_sessions chat
                left join public.users app_user
                  on app_user.id = chat.user_id
                 and app_user.organization_id = chat.organization_id
                where app_user.id is null
                """, Integer.class);

        assertThat(orphanCount).isZero();
        Boolean chatMissing = jdbcTemplate.queryForObject("""
                select not exists (
                    select 1
                    from public.chat_sessions
                    where id = ?
                )
                """, Boolean.class, chatId);

        assertThat(
                userExists(fixture.userId())
                        || Boolean.TRUE.equals(chatMissing)
        ).isTrue();
    }

    private String createUserOutcome(String email) {
        try {
            userService.create(
                    new CreateUserRequest(
                            ORGANIZATION_A_ID,
                            email,
                            VALID_PASSWORD,
                            "Race User",
                            Set.of("USER")
                    ),
                    adminPrincipal()
            );
            return "SUCCESS";
        } catch (ConflictException exception) {
            return "CONFLICT";
        }
    }

    private String disableOutcome(UUID userId) {
        try {
            userService.updateEnabled(
                    userId,
                    new UpdateUserEnabledRequest(false, 0L),
                    superAdminPrincipal()
            );
            return "SUCCESS";
        } catch (ForbiddenOperationException exception) {
            return "FORBIDDEN";
        }
    }

    private String roleRemovalOutcome(UUID userId) {
        try {
            userService.updateRoles(
                    userId,
                    new UpdateUserRolesRequest(Set.of("USER"), 0L),
                    superAdminPrincipal()
            );
            return "SUCCESS";
        } catch (ForbiddenOperationException exception) {
            return "FORBIDDEN";
        }
    }

    private String deletionOutcome(DeletionFixture fixture) {
        try {
            userService.permanentlyDelete(
                    fixture.userId(),
                    new PermanentDeleteUserRequest(fixture.email(), 0L),
                    superAdminPrincipal()
            );
            return "DELETE_SUCCESS";
        } catch (RuntimeException exception) {
            return "DELETE_FAILED";
        }
    }

    private String chatCreationOutcome(
            DeletionFixture fixture,
            UUID chatId
    ) {
        try {
            TransactionTemplate transaction =
                    new TransactionTemplate(transactionManager);
            transaction.executeWithoutResult(status -> insertChatSession(
                    chatId,
                    fixture.userId(),
                    fixture.organizationId()
            ));
            return "CHAT_SUCCESS";
        } catch (RuntimeException exception) {
            return "CHAT_FAILED";
        }
    }

    private List<String> runConcurrently(
            Callable<String> first,
            Callable<String> second
    ) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor =
                     Executors.newFixedThreadPool(2)) {
            Future<String> firstFuture = executor.submit(() -> {
                ready.countDown();
                awaitLatch(start, "start latch for first task");
                return first.call();
            });

            Future<String> secondFuture = executor.submit(() -> {
                ready.countDown();
                awaitLatch(start, "start latch for second task");
                return second.call();
            });

            if (!ready.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "Concurrent tasks did not become ready in time"
                );
            }

            start.countDown();

            return List.of(
                    firstFuture.get(20, TimeUnit.SECONDS),
                    secondFuture.get(20, TimeUnit.SECONDS)
            );
        }
    }

    private void awaitLatch(
            CountDownLatch latch,
            String description
    ) throws InterruptedException {
        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException(
                    "Timed out waiting for " + description
            );
        }
    }

    private DeletionFixture disabledUserFixture(String email) {
        UUID organizationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        insertOrganization(
                organizationId,
                "Deletion " + organizationId,
                true
        );
        insertUser(
                userId,
                organizationId,
                email,
                false,
                "USER",
                Instant.now().minus(Duration.ofDays(10))
        );
        return new DeletionFixture(userId, organizationId, email);
    }

    private void insertConsistentUsageRollup(
            UUID userId,
            UUID organizationId
    ) {
        jdbcTemplate.update(
                """
                insert into public.usage_daily_user_model_rollups (
                    usage_date,
                    organization_id,
                    user_id,
                    model,
                    input_tokens,
                    output_tokens,
                    total_tokens,
                    cost_usd,
                    assistant_message_count,
                    failed_message_count,
                    completed_response_count,
                    refused_response_count,
                    incomplete_response_count,
                    partial_input_tokens,
                    partial_output_tokens,
                    available_usage_message_count,
                    partial_usage_message_count,
                    missing_usage_message_count,
                    usage_not_applicable_message_count,
                    priced_message_count,
                    free_message_count,
                    unpriced_message_count,
                    pricing_failed_message_count,
                    pricing_not_applicable_message_count,
                    created_at,
                    updated_at
                ) values (
                    current_date,
                    ?,
                    ?,
                    'mock-safeai',
                    10,
                    5,
                    15,
                    0,
                    1,
                    0,
                    1,
                    0,
                    0,
                    0,
                    0,
                    1,
                    0,
                    0,
                    0,
                    0,
                    1,
                    0,
                    0,
                    0,
                    current_timestamp,
                    current_timestamp
                )
                """,
                organizationId,
                userId
        );
    }

    private void assertDeletionConflict(
            DeletionFixture fixture,
            String message
    ) {
        assertThatThrownBy(() -> userService.permanentlyDelete(
                fixture.userId(),
                new PermanentDeleteUserRequest(fixture.email(), 0L),
                superAdminPrincipal()
        ))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining(message);

        assertThat(userExists(fixture.userId())).isTrue();
    }

    private SafeAiUserPrincipal adminPrincipal() {
        return SafeAiUserPrincipal.accessTokenPrincipal(
                ADMIN_ID,
                ORGANIZATION_A_ID,
                0L,
                0L,
                Set.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
    }

    private SafeAiUserPrincipal superAdminPrincipal() {
        return SafeAiUserPrincipal.accessTokenPrincipal(
                SUPER_ADMIN_ID,
                PLATFORM_ORGANIZATION_ID,
                0L,
                0L,
                Set.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))
        );
    }

    private record DeletionFixture(
            UUID userId,
            UUID organizationId,
            String email
    ) {
    }
}
