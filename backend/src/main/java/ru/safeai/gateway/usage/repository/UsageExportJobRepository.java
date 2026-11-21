// backend/src/main/java/ru/safeai/gateway/usage/repository/UsageExportJobRepository.java
package ru.safeai.gateway.usage.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.safeai.gateway.common.budget.PlatformBudgetClassification.Status;
import ru.safeai.gateway.usage.config.UsageJdbcClients;
import ru.safeai.gateway.usage.dto.UsageExportRequest;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class UsageExportJobRepository {
    private static final long QUEUE_LOCK = 8_026_119_801L;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public record Owner(UUID id, UUID organization, long token, long orgVersion, String role) {}
    public record Job(UUID id, String state, Instant expiresAt, String failure) {}
    public record Claim(UUID id, UUID lease, Owner owner, UsageExportRequest request) {}
    public record Snapshot(long queued, long running, long expiredLeases, long failed) {}

    public UsageExportJobRepository(UsageJdbcClients clients, PlatformTransactionManager manager) {
        this.jdbc = JdbcClient.create(clients.rollup());
        this.tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** Separate statements after obtaining the transaction lock: no stale admission snapshot. */
    public Optional<Job> enqueue(Owner owner, UsageExportRequest request) {
        return Objects.requireNonNull(tx.execute(status -> {
            if (admissionBusy()) return Optional.empty();
            purge();
            if (!authorized(owner)) return Optional.empty();
            return jdbc.sql("""
                    insert into public.usage_export_jobs
                        (id, owner_id, owner_org, token_version, org_version, owner_role, kind, date_from,
                         date_to, organization_id, user_id, model, report_order, budget_status, created_at, expires_at)
                    select :id, :owner, :org, :token, :version, :role, :kind, :from, :to,
                           :scope, :user, :model, :ordering, :budget, n, n + interval '15 minutes'
                    from (select clock_timestamp() n) stamp
                    where (select count(*) from public.usage_export_jobs) < 8
                      and (select count(*) from public.usage_export_jobs where owner_id = :owner) < 2
                      and (select count(*) from public.usage_export_jobs where state = 'QUEUED') < 4
                      and not exists (select 1 from public.usage_export_jobs
                                      where owner_id = :owner and state in ('QUEUED','RUNNING'))
                    returning id, state, expires_at, failure
                    """).param("id", UUID.randomUUID()).param("owner", owner.id())
                    .param("org", owner.organization()).param("token", owner.token())
                    .param("version", owner.orgVersion()).param("role", owner.role())
                    .param("kind", request.kind().name()).param("from", Timestamp.from(request.dateFrom()))
                    .param("to", Timestamp.from(request.dateTo())).param("scope", request.organizationId(), java.sql.Types.OTHER)
                    .param("user", request.userId(), java.sql.Types.OTHER).param("model", request.model())
                    .param("ordering", request.order()).param("budget", request.budgetStatus() == null
                            ? null : request.budgetStatus().name()).query(UsageExportJobRepository::job).optional();
        }));
    }

    public Optional<Claim> claim() {
        return Objects.requireNonNull(tx.execute(status -> {
            if (admissionBusy()) return Optional.empty();
            purge();
            jdbc.sql("""
                    update public.usage_export_jobs set state = 'FAILED', failure = 'REPORT_UNAVAILABLE',
                        lease_token = null, lease_until = null
                    where state = 'RUNNING' and lease_until <= clock_timestamp() and attempts = 3
                      and expires_at > clock_timestamp()
                    """).update();
            return jdbc.sql("""
                    with candidate as (
                        select id from public.usage_export_jobs j
                        where expires_at > clock_timestamp() and attempts < 3
                          and ((state = 'RUNNING' and lease_until <= clock_timestamp())
                            or (state = 'QUEUED' and not exists
                                (select 1 from public.usage_export_jobs where state = 'RUNNING')))
                        order by created_at, id limit 1 for update skip locked
                    )
                    update public.usage_export_jobs j set state = 'RUNNING', attempts = attempts + 1,
                        lease_token = :lease, lease_until = least(expires_at, clock_timestamp() + interval '2 minutes')
                    from candidate where j.id = candidate.id
                    returning j.id, j.lease_token, j.owner_id, j.owner_org, j.token_version, j.org_version,
                        j.owner_role, j.kind, j.date_from, j.date_to, j.organization_id, j.user_id,
                        j.model, j.report_order, j.budget_status
                    """).param("lease", UUID.randomUUID()).query(UsageExportJobRepository::claimRow).optional();
        }));
    }

    public boolean finish(Claim claim, byte[] csv, String failure) {
        Objects.requireNonNull(claim, "claim");
        if ((csv == null) == (failure == null) || (csv != null && (csv.length == 0 || csv.length > 8_388_608))
                || (failure != null && !"LIMIT_EXCEEDED".equals(failure) && !"REPORT_UNAVAILABLE".equals(failure))) {
            throw new IllegalArgumentException("Invalid export result");
        }
        return Boolean.TRUE.equals(tx.execute(status -> jdbc.sql("""
                update public.usage_export_jobs j set state = :state, csv = :csv, failure = :failure,
                    lease_token = null, lease_until = null
                where j.id = :id and j.state = 'RUNNING' and j.lease_token = :lease
                  and j.lease_until > clock_timestamp() and j.expires_at > clock_timestamp()
                  and (:state = 'FAILED' or exists (
                      select 1 from public.users u join public.organizations o on o.id = u.organization_id
                      join public.user_roles ur on ur.user_id = u.id join public.roles r on r.id = ur.role_id
                      where u.id = j.owner_id and u.organization_id = j.owner_org and u.enabled and o.enabled
                        and u.token_version = j.token_version and o.auth_version = j.org_version
                        and 'ROLE_' || r.name = j.owner_role
                        and (select count(*) from public.user_roles where user_id = u.id) = 1))
                """).param("state", csv == null ? "FAILED" : "READY")
                .param("csv", csv, java.sql.Types.BINARY).param("failure", failure)
                .param("id", claim.id()).param("lease", claim.lease()).update() == 1));
    }

    // Authoritative database check, never the optional Redis authentication cache.
    public boolean authorized(Owner owner) {
        return jdbc.sql("""
                select exists (select 1 from public.users u join public.organizations o on o.id = u.organization_id
                    join public.user_roles ur on ur.user_id = u.id join public.roles r on r.id = ur.role_id
                    where u.id = :owner and u.organization_id = :org and u.enabled and o.enabled
                      and u.token_version = :token and o.auth_version = :version
                      and 'ROLE_' || r.name = :role
                      and (select count(*) from public.user_roles where user_id = u.id) = 1)
                """).param("owner", owner.id()).param("org", owner.organization())
                .param("token", owner.token()).param("version", owner.orgVersion()).param("role", owner.role())
                .query(Boolean.class).single();
    }

    private static final String OWNED = """
            from public.usage_export_jobs j join public.users u on u.id = j.owner_id
            join public.organizations o on o.id = j.owner_org
            join public.user_roles ur on ur.user_id = u.id join public.roles r on r.id = ur.role_id
            where j.id = :id and j.owner_id = :owner and j.owner_org = :org
              and j.token_version = :token and j.org_version = :version and j.owner_role = :role
              and j.expires_at > clock_timestamp() and u.enabled and o.enabled and u.organization_id = o.id
              and u.token_version = j.token_version and o.auth_version = j.org_version
              and 'ROLE_' || r.name = j.owner_role
              and (select count(*) from public.user_roles where user_id = u.id) = 1
            """;

    public Optional<Job> status(UUID id, Owner owner) {
        return owned("select j.id, j.state, j.expires_at, j.failure\n" + OWNED, id, owner)
                .query(UsageExportJobRepository::job).optional();
    }

    public Optional<byte[]> download(UUID id, Owner owner) {
        return owned("select j.csv\n" + OWNED + " and j.state = 'READY'", id, owner)
                .query((rs, row) -> rs.getBytes(1)).optional();
    }

    private JdbcClient.StatementSpec owned(String sql, UUID id, Owner owner) {
        return jdbc.sql(sql).param("id", id).param("owner", owner.id())
                .param("org", owner.organization()).param("token", owner.token())
                .param("version", owner.orgVersion()).param("role", owner.role());
    }

    public Snapshot snapshot() {
        return jdbc.sql("""
                select count(*) filter (where state = 'QUEUED') as queued,
                       count(*) filter (where state = 'RUNNING') as running,
                       count(*) filter (where state = 'RUNNING' and lease_until <= clock_timestamp()) as expired,
                       count(*) filter (where state = 'FAILED') as failed
                from public.usage_export_jobs where expires_at > clock_timestamp()
                """).query((rs, row) -> new Snapshot(rs.getLong("queued"), rs.getLong("running"),
                        rs.getLong("expired"), rs.getLong("failed"))).single();
    }

    private boolean admissionBusy() {
        return jdbc.sql("select not pg_try_advisory_xact_lock(:key)").param("key", QUEUE_LOCK)
                .query(Boolean.class).single();
    }

    private void purge() {
        jdbc.sql("delete from public.usage_export_jobs where expires_at <= clock_timestamp()").update();
    }

    private static Job job(ResultSet rs, int row) throws SQLException {
        return new Job(rs.getObject("id", UUID.class), rs.getString("state"),
                rs.getTimestamp("expires_at").toInstant(), rs.getString("failure"));
    }

    private static Claim claimRow(ResultSet rs, int row) throws SQLException {
        var owner = new Owner(rs.getObject("owner_id", UUID.class), rs.getObject("owner_org", UUID.class),
                rs.getLong("token_version"), rs.getLong("org_version"), rs.getString("owner_role"));
        String budget = rs.getString("budget_status");
        var request = new UsageExportRequest(UsageExportRequest.Kind.valueOf(rs.getString("kind")),
                rs.getTimestamp("date_from").toInstant(), rs.getTimestamp("date_to").toInstant(),
                rs.getObject("organization_id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getString("model"), rs.getString("report_order"), budget == null ? null : Status.valueOf(budget));
        return new Claim(rs.getObject("id", UUID.class), rs.getObject("lease_token", UUID.class), owner, request);
    }
}
