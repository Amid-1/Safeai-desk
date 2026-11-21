// backend/src/main/java/ru/safeai/gateway/usage/service/UsageExportService.java
package ru.safeai.gateway.usage.service;

import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.concurrent.DelegatingSecurityContextRunnable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.usage.dto.UsageExportRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Ограниченная очередь одного экземпляра backend; без хранения CSV в PostgreSQL или Redis. */
@Service
public class UsageExportService {
    private static final int MAX_JOBS = 8;
    private static final Duration TTL = Duration.ofMinutes(15);
    private final Map<UUID, Job> jobs = new HashMap<>();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), Thread.ofPlatform().daemon().name("usage-export-", 0).factory());
    private final Clock clock;
    private final UsageQueryService queries;
    private final UsagePhysicalSpendService physical;

    public UsageExportService(Clock clock, UsageQueryService queries, UsagePhysicalSpendService physical) {
        this.clock = clock;
        this.queries = queries;
        this.physical = physical;
    }

    public enum State { QUEUED, RUNNING, READY, FAILED }
    public record Status(UUID id, State state, Instant expiresAt, String failure) {}
    private record Owner(UUID id, UUID organization, long token, long organizationVersion, List<String> roles) {
        static Owner of(SafeAiUserPrincipal actor) {
            UsageAccessPolicy.requireReader(actor);
            return new Owner(actor.getId(), actor.getOrganizationId(), actor.getTokenVersion(),
                    actor.getOrganizationAuthVersion(), List.copyOf(actor.authorityNames()));
        }
    }

    private static final class Job {
        private final UUID id = UUID.randomUUID();
        private final Owner owner;
        private final Instant expiresAt;
        private State state = State.QUEUED;
        private byte[] bytes;
        private String failure;

        private Job(Owner owner, Instant expiresAt) {
            this.owner = owner;
            this.expiresAt = expiresAt;
        }
        private Status status() { return new Status(id, state, expiresAt, failure); }
    }

    public synchronized Status start(UsageExportRequest request, SafeAiUserPrincipal actor) {
        Owner owner = Owner.of(actor);
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
        purge();
        if (jobs.values().stream().filter(j -> j.owner.id().equals(owner.id())).count() >= 2) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Допустимо не более двух заданий за 15 минут");
        }
        if (jobs.size() >= MAX_JOBS || jobs.values().stream().anyMatch(j -> j.owner.id().equals(owner.id())
                && (j.state == State.QUEUED || j.state == State.RUNNING))) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Очередь экспорта занята");
        }
        var job = new Job(owner, clock.instant().plus(TTL));
        jobs.put(job.id, job);
        var effective = new UsageExportRequest(request.kind(), request.dateFrom(), request.dateTo(),
                platform ? request.organizationId() : owner.organization(), request.userId(),
                request.model() == null || request.model().isBlank() ? null : request.model().strip(),
                request.order(), request.budgetStatus());
        var principal = SafeAiUserPrincipal.accessTokenPrincipal(owner.id(), owner.organization(),
                owner.token(), owner.organizationVersion(), actor.getAuthorities());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities()));
        try {
            worker.execute(new DelegatingSecurityContextRunnable(() -> generate(job, effective, principal), context));
        } catch (RejectedExecutionException e) {
            jobs.remove(job.id);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Очередь экспорта занята");
        }
        return job.status();
    }

    private void generate(Job job, UsageExportRequest request, SafeAiUserPrincipal actor) {
        synchronized (this) {
            if (jobs.get(job.id) != job || !clock.instant().isBefore(job.expiresAt)) return;
            job.state = State.RUNNING;
        }
        try {
            Instant generatedAt = clock.instant();
            List<?> rows = request.kind() == UsageExportRequest.Kind.PROVIDERS
                    ? physical.providers(request.dateFrom(), request.dateTo(), request.organizationId(),
                        request.model(), request.userId(), actor)
                    : queries.exportRows(request, actor, generatedAt);
            byte[] bytes = UsageExportCsv.encode(request, rows, generatedAt);
            synchronized (this) {
                if (jobs.get(job.id) == job && clock.instant().isBefore(job.expiresAt)) {
                    job.bytes = bytes;
                    job.state = State.READY;
                }
            }
        } catch (UsageExportCsv.LimitExceeded e) {
            fail(job, "LIMIT_EXCEEDED");
        } catch (RuntimeException e) {
            // Не передаём наружу SQL, имена пользователей и внутренние ошибки.
            fail(job, "REPORT_UNAVAILABLE");
        }
    }

    private synchronized void fail(Job job, String reason) {
        if (jobs.get(job.id) == job) {
            job.failure = reason;
            job.state = State.FAILED;
        }
    }

    public synchronized Status status(UUID id, SafeAiUserPrincipal actor) {
        return owned(id, actor).status();
    }

    public synchronized byte[] download(UUID id, SafeAiUserPrincipal actor) {
        Job job = owned(id, actor);
        if (job.state != State.READY) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Экспорт ещё недоступен");
        }
        return job.bytes.clone();
    }

    private Job owned(UUID id, SafeAiUserPrincipal actor) {
        Owner owner = Owner.of(actor);
        purge();
        Job job = jobs.get(id);
        if (job == null || !job.owner.equals(owner)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Экспорт не найден");
        }
        return job;
    }

    @Scheduled(fixedDelay = 60_000)
    public synchronized void purge() {
        Instant now = clock.instant();
        jobs.values().removeIf(job -> !now.isBefore(job.expiresAt));
    }

    @PreDestroy
    public synchronized void close() {
        worker.shutdownNow();
        jobs.clear();
    }
}
