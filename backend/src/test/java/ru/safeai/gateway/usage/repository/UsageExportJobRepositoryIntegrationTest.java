// backend/src/test/java/ru/safeai/gateway/usage/repository/UsageExportJobRepositoryIntegrationTest.java
package ru.safeai.gateway.usage.repository;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.safeai.gateway.usage.config.UsageJdbcClients;
import ru.safeai.gateway.usage.config.UsageProperties;
import ru.safeai.gateway.usage.dto.UsageExportRequest;
import ru.safeai.gateway.usage.repository.UsageExportJobRepository.Owner;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("integration")
@Testcontainers
class UsageExportJobRepositoryIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg16");
    private static JdbcTemplate jdbc;
    private static UsageJdbcClients clients;
    private static DataSourceTransactionManager manager;

    @BeforeAll
    static void setup() throws Exception {
        var ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(ds);
        manager = new DataSourceTransactionManager(ds);
        clients = new UsageJdbcClients(ds, new UsageProperties(null, null, null, null, null, null, null, null, null));
        jdbc.execute("""
                create table public.organizations (
                    id uuid primary key, name varchar(255) not null,
                    normalized_name varchar(255) not null unique,
                    enabled boolean, auth_version bigint
                );
                create table public.users (
                    id uuid primary key, organization_id uuid,
                    email varchar(255) not null unique, password_hash varchar(255) not null,
                    enabled boolean, token_version bigint
                );
                create table public.roles (id uuid primary key, name text);
                create table public.user_roles (user_id uuid, role_id uuid);
                insert into public.roles (id, name) values
                    ('00000000-0000-0000-0000-000000000001','ADMIN'),
                    ('00000000-0000-0000-0000-000000000002','USER');
                """);
        String v98 = new ClassPathResource("db/migration/V98__usage_export_jobs.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        String v99 = new ClassPathResource("db/migration/V99__usage_export_job_guard.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            jdbc.execute(v98);
            jdbc.execute(v99);
        });
    }

    @BeforeEach
    void reset() {
        jdbc.execute("truncate public.usage_export_jobs, public.user_roles, public.users, public.organizations");
    }

    @Test
    void replicasShareAdmissionAndRecoverAbandonedLeaseWithoutPublishingStaleResult() throws Exception {
        var a = replica();
        var b = replica();
        Owner owner = owner();
        var request = request(owner);
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(12)) {
            var futures = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 24; i++) {
                var repo = i % 2 == 0 ? a : b;
                futures.add(pool.submit(() -> {
                    if (!gate.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("gate timeout");
                    return repo.enqueue(owner, request).isPresent();
                }));
            }
            gate.countDown();
            int accepted = 0;
            for (var f : futures) if (f.get(15, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(1);
        }
        var first = a.claim().orElseThrow();
        assertThat(b.claim()).isEmpty();
        assertThat(b.status(first.id(), owner).orElseThrow().state()).isEqualTo("RUNNING");
        expireLease(first.id());
        var recovered = b.claim().orElseThrow();
        assertThat(recovered.id()).isEqualTo(first.id());
        assertThat(recovered.lease()).isNotEqualTo(first.lease());
        assertThat(a.finish(first, new byte[]{1}, null)).isFalse();
        assertThat(b.finish(recovered, new byte[]{2}, null)).isTrue();
        assertThat(replica().download(first.id(), owner).orElseThrow()).containsExactly((byte) 2);
        rejectsSql("23514", "V98 export is terminal or expired", () -> jdbc.update(
                "update public.usage_export_jobs set csv = ? where id = ?", new byte[]{3}, first.id()));
        rejectsSql("23514", "V98 export retention", () -> jdbc.update(
                "delete from public.usage_export_jobs where id = ?", first.id()));
        assertThat(b.download(first.id(), owner).orElseThrow()).containsExactly((byte) 2);
        assertThat(b.download(first.id(), owner())).isEmpty();
        jdbc.update("update public.users set token_version = token_version + 1 where id = ?", owner.id());
        assertThat(b.download(first.id(), owner)).isEmpty();
        assertThat(b.status(first.id(), owner)).isEmpty();
        assertThat(b.authorized(owner)).isFalse();
    }

    @Test
    void globalAndPerOwnerBoundsAreSharedAndFailureNeverContainsCsv() {
        var repo = replica();
        Owner first = owner();
        var firstJob = repo.enqueue(first, request(first)).orElseThrow();
        assertThat(repo.enqueue(first, request(first))).isEmpty();
        var claim = repo.claim().orElseThrow();
        assertThat(repo.finish(claim, null, "REPORT_UNAVAILABLE")).isTrue();
        assertThat(repo.download(firstJob.id(), first)).isEmpty();
        assertThat(repo.enqueue(first, request(first))).isPresent();
        assertThat(repo.finish(repo.claim().orElseThrow(), new byte[]{1}, null)).isTrue();
        assertThat(repo.enqueue(first, request(first))).isEmpty();
        for (int i = 0; i < 4; i++) {
            Owner other = owner();
            assertThat(repo.enqueue(other, request(other))).isPresent();
        }
        Owner other = owner();
        assertThat(replica().enqueue(other, request(other))).isEmpty();
        assertThat(repo.snapshot().queued()).isEqualTo(4);
    }

    @Test
    void globalEightJobLimitIsIndependentOfQueuedAndOwnerLimits() {
        var repo = replica();
        for (int i = 0; i < 8; i++) {
            Owner actor = owner();
            var job = repo.enqueue(actor, request(actor)).orElseThrow();
            var claim = repo.claim().orElseThrow();
            assertThat(claim.id()).isEqualTo(job.id());
            assertThat(repo.finish(claim, new byte[]{1}, null)).isTrue();
        }
        assertThat(repo.snapshot().queued()).isZero();
        assertThat(repo.snapshot().running()).isZero();
        Owner ninth = owner();
        assertThat(replica().enqueue(ninth, request(ninth))).isEmpty();
        rejectsSql("23514", "V98 export admission bounds", () -> directInsert(ninth, ninth.organization()));
        assertThat(jdbc.queryForObject("select count(*) from public.usage_export_jobs", Long.class))
                .isEqualTo(8L);
    }

    @Test
    void concurrentReplicasClaimExactlyOneOfFourDistinctJobs() throws Exception {
        var repo = replica();
        for (int i = 0; i < 4; i++) {
            Owner actor = owner();
            assertThat(repo.enqueue(actor, request(actor))).isPresent();
        }
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<Optional<UsageExportJobRepository.Claim>>>();
            for (int i = 0; i < 8; i++) {
                var worker = replica();
                futures.add(pool.submit(() -> {
                    if (!gate.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("gate timeout");
                    return worker.claim();
                }));
            }
            gate.countDown();
            var claims = new ArrayList<UsageExportJobRepository.Claim>();
            for (var future : futures) future.get(15, TimeUnit.SECONDS).ifPresent(claims::add);
            assertThat(claims).hasSize(1);
            assertThat(repo.snapshot().running()).isEqualTo(1);
            assertThat(repo.snapshot().queued()).isEqualTo(3);
            assertThat(repo.finish(claims.getFirst(), new byte[]{1}, null)).isTrue();
        }
    }

    @Test
    void thirdExpiredLeaseFailsAndEveryStaleWorkerIsFenced() {
        var repo = replica();
        Owner actor = owner();
        UUID id = repo.enqueue(actor, request(actor)).orElseThrow().id();
        var first = repo.claim().orElseThrow();
        expireLease(id);
        assertThat(repo.snapshot().expiredLeases()).isEqualTo(1);
        rejectsSql("23514", "V99 invalid export lease", () -> jdbc.update("""
                update public.usage_export_jobs set attempts = 2,
                    lease_until = clock_timestamp() + interval '1 minute' where id = ?
                """, id));
        var second = replica().claim().orElseThrow();
        assertThat(second.id()).isEqualTo(id);
        assertThat(second.lease()).isNotEqualTo(first.lease());
        expireLease(id);
        var third = replica().claim().orElseThrow();
        assertThat(third.id()).isEqualTo(id);
        assertThat(third.lease()).isNotIn(first.lease(), second.lease());
        assertThat(repo.finish(first, new byte[]{1}, null)).isFalse();
        assertThat(repo.finish(second, null, "REPORT_UNAVAILABLE")).isFalse();
        expireLease(id);
        assertThat(repo.finish(third, new byte[]{3}, null)).isFalse();
        assertThat(repo.claim()).isEmpty();
        var failed = repo.status(id, actor).orElseThrow();
        assertThat(failed.state()).isEqualTo("FAILED");
        assertThat(failed.failure()).isEqualTo("REPORT_UNAVAILABLE");
        assertThat(repo.download(id, actor)).isEmpty();
        assertThat(repo.snapshot().failed()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select attempts from public.usage_export_jobs where id = ?", Integer.class, id)).isEqualTo(3);
    }

    @Test
    void expiredFileIsInvisibleBeforePurgeAndCapacityIsRecovered() {
        var repo = replica();
        Owner actor = owner();
        UUID id = repo.enqueue(actor, request(actor)).orElseThrow().id();
        assertThat(repo.finish(repo.claim().orElseThrow(), new byte[]{1}, null)).isTrue();
        fixtureTimeUpdate(id, """
                update public.usage_export_jobs set created_at = created_at - interval '16 minutes',
                    expires_at = expires_at - interval '16 minutes' where id = ?
                """);
        assertThat(repo.status(id, actor)).isEmpty();
        assertThat(repo.download(id, actor)).isEmpty();
        assertThat(jdbc.queryForObject(
                "select count(*) from public.usage_export_jobs where id = ?", Long.class, id)).isEqualTo(1L);
        assertThat(repo.claim()).isEmpty();
        assertThat(jdbc.queryForObject(
                "select count(*) from public.usage_export_jobs where id = ?", Long.class, id)).isZero();
        assertThat(repo.enqueue(actor, request(actor))).isPresent();
    }

    @Test
    void invalidResultsCannotChangeRunningJob() {
        var repo = replica();
        Owner actor = owner();
        UUID id = repo.enqueue(actor, request(actor)).orElseThrow().id();
        var claim = repo.claim().orElseThrow();
        assertThatThrownBy(() -> repo.finish(claim, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repo.finish(claim, new byte[0], null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repo.finish(claim, new byte[8_388_609], null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repo.finish(claim, new byte[]{1}, "REPORT_UNAVAILABLE"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repo.finish(claim, null, "UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repo.status(id, actor).orElseThrow().state()).isEqualTo("RUNNING");
        assertThat(repo.download(id, actor)).isEmpty();
        assertThat(repo.finish(claim, new byte[8_388_608], null)).isTrue();
        assertThat(repo.download(id, actor).orElseThrow()).hasSize(8_388_608);
    }

    @Test
    void everyAuthorizationDimensionBlocksReadyFileAndNewJob() {
        var changes = java.util.List.<java.util.function.Consumer<Owner>>of(
                actor -> jdbc.update("update public.users set enabled = false where id = ?", actor.id()),
                actor -> jdbc.update("update public.organizations set enabled = false where id = ?",
                        actor.organization()),
                actor -> jdbc.update("update public.organizations set auth_version = 2 where id = ?",
                        actor.organization()),
                actor -> jdbc.update("update public.users set token_version = 2 where id = ?", actor.id()),
                actor -> jdbc.update("""
                        update public.user_roles set role_id = '00000000-0000-0000-0000-000000000002'
                        where user_id = ?
                        """, actor.id()),
                actor -> jdbc.update("""
                        insert into public.user_roles (user_id, role_id)
                        values (?, '00000000-0000-0000-0000-000000000002')
                        """, actor.id()));
        var repo = replica();
        for (var change : changes) {
            Owner actor = owner();
            UUID id = repo.enqueue(actor, request(actor)).orElseThrow().id();
            assertThat(repo.finish(repo.claim().orElseThrow(), new byte[]{1}, null)).isTrue();
            assertThat(repo.download(id, actor)).isPresent();
            assertThat(repo.status(id, owner())).isEmpty();
            change.accept(actor);
            assertThat(repo.authorized(actor)).isFalse();
            assertThat(repo.status(id, actor)).isEmpty();
            assertThat(repo.download(id, actor)).isEmpty();
            assertThat(repo.enqueue(actor, request(actor))).isEmpty();
        }
    }

    @Test
    void databaseRejectsDirectSqlWithStaleIsolationInvalidLeaseAndRevokedOwner() {
        var repo = replica();
        Owner owner = owner();
        UUID id = repo.enqueue(owner, request(owner)).orElseThrow().id();
        var rr = new TransactionTemplate(manager);
        rr.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        Owner second = owner();
        rejectsSql("25000", "V99 export requires READ COMMITTED", () ->
                rr.executeWithoutResult(status -> directInsert(second, second.organization())));
        rejectsSql("25000", "V99 export requires READ COMMITTED", () ->
                rr.executeWithoutResult(status -> jdbc.update(
                        "update public.usage_export_jobs set state = state where id = ?", id)));

        rejectsSql("23514", "V99 invalid export lease", () -> jdbc.update("""
                update public.usage_export_jobs set state = 'RUNNING', attempts = 1,
                    lease_token = ?, lease_until = expires_at where id = ?
                """, UUID.randomUUID(), id));
        rejectsSql("23514", "V99 export identity is immutable", () -> jdbc.update("""
                update public.usage_export_jobs set state = 'RUNNING', attempts = 1,
                    lease_token = ?, lease_until = clock_timestamp() + interval '1 minute', model = 'changed'
                where id = ?
                """, UUID.randomUUID(), id));

        var claim = repo.claim().orElseThrow();
        jdbc.update("update public.users set token_version = token_version + 1 where id = ?", owner.id());
        assertThat(repo.finish(claim, new byte[]{1}, null)).isFalse();
        rejectsSql("23514", "V99 export authorization changed", () -> jdbc.update("""
                update public.usage_export_jobs set state = 'READY', csv = ?, lease_token = null,
                    lease_until = null where id = ?
                """, new byte[]{1}, id));
        assertThat(repo.finish(claim, null, "REPORT_UNAVAILABLE")).isTrue();
        assertThat(repo.download(id, owner)).isEmpty();
        rejectsSql("23514", "V99 export authorization changed", () -> directInsert(owner, owner.organization()));
        rejectsSql("23514", "check constraint", () -> directInsert(second, owner.organization()));
    }

    private static void directInsert(Owner actor, UUID scope) {
        jdbc.update("""
                insert into public.usage_export_jobs
                    (id, owner_id, owner_org, token_version, org_version, owner_role, kind,
                     date_from, date_to, organization_id, created_at, expires_at)
                select ?, ?, ?, ?, ?, ?, 'SUMMARY', stamp - interval '1 hour', stamp, ?,
                    stamp, stamp + interval '15 minutes'
                from (select clock_timestamp() as stamp) t
                """, UUID.randomUUID(), actor.id(), actor.organization(), actor.token(),
                actor.orgVersion(), actor.role(), scope);
    }

    private static void rejectsSql(String state, String message, Runnable operation) {
        var error = assertThrows(DataAccessException.class, operation::run);
        var cause = error.getMostSpecificCause();
        assertThat(cause).isInstanceOf(SQLException.class).hasMessageContaining(message);
        assertThat(((SQLException) cause).getSQLState()).isEqualTo(state);
    }

    private static void expireLease(UUID id) {
        fixtureTimeUpdate(id, """
                update public.usage_export_jobs set lease_until = clock_timestamp() - interval '1 second'
                where id = ? and state = 'RUNNING'
                """);
    }

    // Fixture owner only. Transaction rollback also restores the trigger if the update fails.
    private static void fixtureTimeUpdate(UUID id, String sql) {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            jdbc.execute("alter table public.usage_export_jobs disable trigger trg_usage_export_job_v98");
            assertThat(jdbc.update(sql, id)).isEqualTo(1);
            jdbc.execute("alter table public.usage_export_jobs enable trigger trg_usage_export_job_v98");
        });
    }

    private static UsageExportJobRepository replica() { return new UsageExportJobRepository(clients, manager); }

    private static Owner owner() {
        UUID user = UUID.randomUUID();
        UUID org = UUID.randomUUID();
        String organizationName = "usage-export-" + org;
        jdbc.update("""
                insert into public.organizations (id, name, normalized_name, enabled, auth_version)
                values (?, ?, ?, true, 1)
                """, org, organizationName, organizationName);
        // This fixture checks authorization state, not password authentication.
        jdbc.update("""
                insert into public.users (id, organization_id, email, password_hash, enabled, token_version)
                values (?, ?, ?, ?, true, 1)
                """, user, org, user + "@example.invalid", "fixture-not-a-login-password-hash");
        jdbc.update("""
                insert into public.user_roles (user_id, role_id)
                values (?, '00000000-0000-0000-0000-000000000001')
                """, user);
        return new Owner(user, org, 1, 1, "ROLE_ADMIN");
    }

    private static UsageExportRequest request(Owner owner) {
        Instant to = Instant.now();
        return new UsageExportRequest(UsageExportRequest.Kind.SUMMARY, to.minusSeconds(3600), to,
                owner.organization(), null, null, null, null);
    }
}
