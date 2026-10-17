package ru.safeai.gateway.usage.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.usage.config.UsageProperties;
import ru.safeai.gateway.usage.repository.UsagePhysicalSpendRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class UsagePhysicalSpendServiceTest {

    private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");

    @Mock UsagePhysicalSpendRepository repository;
    private UsagePhysicalSpendService service;

    @BeforeEach
    void setUp() {
        UsageProperties properties = new UsageProperties(
                Duration.ofDays(30), Duration.ofDays(366), Duration.ofDays(31),
                200, Duration.ofSeconds(10), Duration.ofSeconds(2),
                200, 4, UsageProperties.Rollup.defaults());
        service = new UsagePhysicalSpendService(
                repository,
                new UsageReportExecutor(properties, new SimpleMeterRegistry()),
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void adminCannotObtainUnscopedPhysicalSpend() {
        service.findCoverage(null, null, principal("ROLE_ADMIN"));
        verify(repository).findCoverage(NOW.minus(Duration.ofDays(30)), NOW, ORG);
    }

    @Test
    void superAdminCanReadGlobalPhysicalSpend() {
        service.findCoverage(null, null, principal("ROLE_SUPER_ADMIN"));
        verify(repository).findCoverage(NOW.minus(Duration.ofDays(30)), NOW, null);
    }

    @Test
    void rejectsInvalidRangeBeforeAnyDatabaseCall() {
        assertThatThrownBy(() -> service.findCoverage(NOW, NOW, principal("ROLE_ADMIN")))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(repository);
    }

    private static SafeAiUserPrincipal principal(String role) {
        return SafeAiUserPrincipal.accessTokenPrincipal(
                USER, ORG, 0L, 0L, List.of(new SimpleGrantedAuthority(role)));
    }
}
