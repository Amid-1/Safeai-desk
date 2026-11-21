// backend/src/main/java/ru/safeai/gateway/usage/service/UsageExportService.java
package ru.safeai.gateway.usage.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.usage.dto.UsageExportRequest;
import ru.safeai.gateway.usage.repository.UsageExportJobRepository;
import ru.safeai.gateway.usage.repository.UsageExportJobRepository.Owner;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** PostgreSQL: одна активная аренда на кластер, публикация только по токену аренды. */
@Service
public class UsageExportService {
    private final UsageExportJobRepository jobs;
    private final Clock clock;
    private final UsageQueryService queries;
    private final UsagePhysicalSpendService physical;
    private final MeterRegistry meters;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("usage-export-", 0).factory());
    private volatile UsageExportJobRepository.Snapshot snapshot;
    private volatile long observedAt;

    public UsageExportService(Clock clock, UsageQueryService queries, UsagePhysicalSpendService physical,
                              UsageExportJobRepository jobs, MeterRegistry meters) {
        this.clock = clock;
        this.queries = queries;
        this.physical = physical;
        this.jobs = jobs;
        this.meters = meters;
        Gauge.builder("safeai.usage.export.queued", this, s -> s.snapshot == null ? Double.NaN : s.snapshot.queued())
                .register(meters);
        Gauge.builder("safeai.usage.export.running", this, s -> s.snapshot == null ? Double.NaN : s.snapshot.running())
                .register(meters);
        Gauge.builder("safeai.usage.export.expired.leases", this,
                s -> s.snapshot == null ? Double.NaN : s.snapshot.expiredLeases()).register(meters);
        Gauge.builder("safeai.usage.export.failed", this, s -> s.snapshot == null ? Double.NaN : s.snapshot.failed())
                .register(meters);
        Gauge.builder("safeai.usage.export.last.observation.timestamp", this, s -> s.observedAt).register(meters);
    }

    public enum State { QUEUED, RUNNING, READY, FAILED }
    public record Status(UUID id, State state, Instant expiresAt, String failure) {}

    public Status start(UsageExportRequest request, SafeAiUserPrincipal actor) {
        Owner owner = owner(actor);
        if (request == null || request.kind() == null || request.dateFrom() == null || request.dateTo() == null
                || !request.dateFrom().isBefore(request.dateTo())
                || Duration.between(request.dateFrom(), request.dateTo()).compareTo(Duration.ofDays(366)) > 0
                || (request.model() != null && (request.model().length() > 100
                || request.model().chars().anyMatch(Character::isISOControl)))
                || (request.order() != null
                && !Set.of("tokens", "total", "cost", "responses", "coverage").contains(request.order()))) {
            throw new BadRequestException("Некорректный период или тип экспорта");
        }
        boolean platform = UsageAccessPolicy.isSuperAdmin(actor);
        if ((!platform && request.organizationId() != null
                && !owner.organization().equals(request.organizationId()))
                || (request.kind() == UsageExportRequest.Kind.ORGANIZATIONS
                && (!platform || request.organizationId() != null))) {
            throw new ForbiddenOperationException("Недопустимая область экспорта");
        }
        if (request.budgetStatus() != null && request.kind() != UsageExportRequest.Kind.ORGANIZATIONS) {
            throw new BadRequestException("Недопустимый фильтр бюджета");
        }
        var effective = new UsageExportRequest(request.kind(), request.dateFrom(), request.dateTo(),
                platform ? request.organizationId() : owner.organization(), request.userId(),
                request.model() == null || request.model().isBlank() ? null : request.model().strip(),
                request.order(), request.budgetStatus());
        var job = jobs.enqueue(owner, effective).orElseThrow(() -> {
            event("rejected");
            return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Очередь экспорта занята");
        });
        event("accepted");
        return status(job);
    }

    @Scheduled(fixedDelay = 2_000)
    public void poll() {
        if (!busy.compareAndSet(false, true)) return;
        try {
            worker.execute(() -> {
                try { runOnce(); }
                finally { busy.set(false); }
            });
        } catch (RejectedExecutionException e) {
            busy.set(false);
        }
    }

    void runOnce() {
        try {
            jobs.claim().ifPresent(this::generate);
            snapshot = jobs.snapshot();
            observedAt = clock.instant().getEpochSecond();
        } catch (RuntimeException e) {
            // Не выдаём READY при отказе БД. Задание останется доступно для восстановления аренды.
            event("storage_error");
        }
    }

    private void generate(UsageExportJobRepository.Claim claim) {
        Owner owner = claim.owner();
        var principal = SafeAiUserPrincipal.accessTokenPrincipal(owner.id(), owner.organization(),
                owner.token(), owner.orgVersion(), List.of(new SimpleGrantedAuthority(owner.role())));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities()));
        byte[] bytes = null;
        String failure = null;
        long started = System.nanoTime();
        try {
            if (!jobs.authorized(owner)) {
                failure = "REPORT_UNAVAILABLE";
            } else {
                SecurityContextHolder.setContext(context);
                var request = claim.request();
                Instant generatedAt = clock.instant();
                List<?> rows = request.kind() == UsageExportRequest.Kind.PROVIDERS
                        ? physical.providers(request.dateFrom(), request.dateTo(), request.organizationId(),
                            request.model(), request.userId(), principal)
                        : queries.exportRows(request, principal, generatedAt);
                if (jobs.authorized(owner)) bytes = UsageExportCsv.encode(request, rows, generatedAt);
                else failure = "REPORT_UNAVAILABLE";
            }
        } catch (UsageExportCsv.LimitExceeded e) {
            failure = "LIMIT_EXCEEDED";
        } catch (org.springframework.dao.DataAccessResourceFailureException
                 | org.springframework.dao.TransientDataAccessException e) {
            // Сетевой отказ/timeout: оставляем аренду до истечения, максимум три захвата.
            event("retry_deferred");
            return;
        } catch (RuntimeException e) {
            failure = "REPORT_UNAVAILABLE";
        } finally {
            SecurityContextHolder.clearContext();
            meters.timer("safeai.usage.export.duration").record(Duration.ofNanos(System.nanoTime() - started));
        }
        if (jobs.finish(claim, bytes, failure)) event(bytes == null ? "failed" : "ready");
        else event("lease_lost");
    }

    public Status status(UUID id, SafeAiUserPrincipal actor) {
        return status(jobs.status(id, owner(actor)).orElseThrow(UsageExportService::notFound));
    }

    public byte[] download(UUID id, SafeAiUserPrincipal actor) {
        Owner owner = owner(actor);
        var job = jobs.status(id, owner).orElseThrow(UsageExportService::notFound);
        if (!"READY".equals(job.state())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Экспорт ещё недоступен");
        }
        return jobs.download(id, owner).orElseThrow(UsageExportService::notFound);
    }

    private static Owner owner(SafeAiUserPrincipal actor) {
        UsageAccessPolicy.requireReader(actor);
        var roles = actor.authorityNames();
        if (roles.size() != 1) throw new ForbiddenOperationException("Недопустимая роль экспорта");
        return new Owner(actor.getId(), actor.getOrganizationId(), actor.getTokenVersion(),
                actor.getOrganizationAuthVersion(), roles.iterator().next());
    }

    private static Status status(UsageExportJobRepository.Job job) {
        return new Status(job.id(), State.valueOf(job.state()), job.expiresAt(), job.failure());
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Экспорт не найден");
    }

    private void event(String result) {
        meters.counter("safeai.usage.export.events", "result", result).increment();
    }

    @PreDestroy
    public void close() { worker.shutdownNow(); }
}
