// backend/src/test/java/ru/safeai/gateway/usage/service/UsageExportServiceTest.java
package ru.safeai.gateway.usage.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.usage.dto.UsageExportRequest;
import ru.safeai.gateway.usage.dto.UsageProviderRow;
import ru.safeai.gateway.usage.repository.UsageExportJobRepository;
import ru.safeai.gateway.usage.repository.UsageExportJobRepository.*;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UsageExportServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");
    private static final UUID ORG = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final Owner OWNER = new Owner(USER, ORG, 1, 1, "ROLE_ADMIN");
    private final UsageExportJobRepository jobs = mock(UsageExportJobRepository.class);
    private final UsageQueryService queries = mock(UsageQueryService.class);
    private final UsagePhysicalSpendService physical = mock(UsagePhysicalSpendService.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final UsageExportService service = new UsageExportService(Clock.fixed(NOW, ZoneOffset.UTC),
            queries, physical, jobs, meters);

    @AfterEach
    void close() {
        service.close();
        meters.close();
    }

    @Test
    void jobIsBoundToOwnerAndAuthVersionAndExportsExactEvidence() {
        UUID id = UUID.randomUUID();
        var request = request(ORG);
        var claim = new Claim(id, UUID.randomUUID(), OWNER, request);
        when(jobs.enqueue(OWNER, request)).thenReturn(Optional.of(new Job(id, "QUEUED", NOW.plusSeconds(900), null)));
        when(jobs.claim()).thenReturn(Optional.of(claim));
        when(jobs.authorized(OWNER)).thenReturn(true);
        when(physical.providers(eq(NOW.minusSeconds(60)), eq(NOW), eq(ORG), isNull(), isNull(), any()))
                .thenAnswer(invocation -> {
                    assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                            .isInstanceOf(SafeAiUserPrincipal.class);
                    return List.of(new UsageProviderRow("mock", "1", "0", "0", "0",
                            "9007199254740993", "0", "0.000000000000000001"));
                });
        when(jobs.finish(eq(claim), any(byte[].class), isNull())).thenAnswer(invocation -> {
            byte[] csv = invocation.getArgument(1);
            assertThat(new String(csv, StandardCharsets.UTF_8))
                    .contains(ORG.toString(), "9007199254740993", "0.000000000000000001");
            return true;
        });
        assertThat(service.start(request(null), actor(USER, ORG, 1, "ROLE_ADMIN")).id()).isEqualTo(id);
        service.runOnce();
        verify(jobs, times(2)).authorized(OWNER);
        verify(jobs).finish(eq(claim), any(byte[].class), isNull());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThatThrownBy(() -> service.status(id, actor(USER, ORG, 2, "ROLE_ADMIN")))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.status(id, actor(UUID.randomUUID(), ORG, 1, "ROLE_ADMIN")))
                .isInstanceOf(ResponseStatusException.class);
        when(jobs.status(id, OWNER)).thenReturn(Optional.of(new Job(id, "RUNNING", NOW.plusSeconds(900), null)));
        assertThatThrownBy(() -> service.download(id, actor(USER, ORG, 1, "ROLE_ADMIN")))
                .isInstanceOf(ResponseStatusException.class);
        verify(jobs, never()).download(any(), any());
    }

    @Test
    void deniesForeignTenantAndOrdinaryUserBeforeQueue() {
        assertThatThrownBy(() -> service.start(request(UUID.randomUUID()), actor(USER, ORG, 1, "ROLE_ADMIN")))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> service.start(request(null), actor(USER, ORG, 1, "ROLE_USER")))
                .isInstanceOf(ForbiddenOperationException.class);
        verifyNoInteractions(jobs);
    }

    @Test
    void csvNeutralizesFormulaAndRejectsOversizedResults() {
        assertThat(UsageExportCsv.cell(" \t=1+1")).isEqualTo("\"' \t=1+1\"");
        assertThat(UsageExportCsv.cell("x;\"y")).isEqualTo("\"x;\"\"y\"");
        assertThatThrownBy(() -> UsageExportCsv.encode(request(null),
                java.util.Collections.nCopies(UsageExportCsv.MAX_ROWS + 1, "row"), NOW))
                .isInstanceOf(UsageExportCsv.LimitExceeded.class);
    }

    @Test
    void failedJobsNeverExposePartialCsvOrInternalErrors() {
        var request = new UsageExportRequest(UsageExportRequest.Kind.SUMMARY, NOW.minusSeconds(60),
                NOW, ORG, null, null, null, null);
        var claim = new Claim(UUID.randomUUID(), UUID.randomUUID(), OWNER, request);
        when(jobs.claim()).thenReturn(Optional.of(claim));
        when(jobs.authorized(OWNER)).thenReturn(true);
        when(queries.exportRows(any(), any(), any())).thenThrow(new UsageExportCsv.LimitExceeded())
                .thenThrow(new IllegalStateException("secret SQL diagnostic"));
        service.runOnce();
        service.runOnce();
        verify(jobs).finish(claim, null, "LIMIT_EXCEEDED");
        verify(jobs).finish(claim, null, "REPORT_UNAVAILABLE");
    }

    @Test
    void revokedOwnerNeverRunsReportAndLostLeaseNeverReportsSuccess() {
        var claim = new Claim(UUID.randomUUID(), UUID.randomUUID(), OWNER, request(ORG));
        when(jobs.claim()).thenReturn(Optional.of(claim));
        when(jobs.authorized(OWNER)).thenReturn(false);
        service.runOnce();
        verifyNoInteractions(queries, physical);
        verify(jobs).finish(claim, null, "REPORT_UNAVAILABLE");
        assertThat(meters.counter("safeai.usage.export.events", "result", "lease_lost").count()).isEqualTo(1);
    }

    private static UsageExportRequest request(UUID organization) {
        return new UsageExportRequest(UsageExportRequest.Kind.PROVIDERS, NOW.minusSeconds(60), NOW,
                organization, null, null, null, null);
    }

    private static SafeAiUserPrincipal actor(UUID id, UUID organization, long version, String role) {
        return SafeAiUserPrincipal.accessTokenPrincipal(id, organization, version, 1,
                List.of(new SimpleGrantedAuthority(role)));
    }
}
