package ru.safeai.gateway.usage.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.usage.config.UsageProperties;
import ru.safeai.gateway.usage.repository.UsageQueryRepository;
import ru.safeai.gateway.usage.repository.UsageRollupStateRepository;
import ru.safeai.gateway.common.exception.ForbiddenOperationException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class UsageOrganizationScopeTest {
    @Test
    void ordinaryAdminAndUserCannotRequestPlatformComparison() {
        var repository = mock(UsageQueryRepository.class);
        var executor = mock(UsageReportExecutor.class);
        var service = new UsageQueryService(repository, mock(UsageRollupStateRepository.class),
                mock(UsageProperties.class), executor, Clock.systemUTC());
        for (String role : List.of("ROLE_ADMIN", "ROLE_USER")) {
            var principal = SafeAiUserPrincipal.accessTokenPrincipal(UUID.randomUUID(), UUID.randomUUID(),
                    0, 0, List.of(new SimpleGrantedAuthority(role)));
            assertThrows(ForbiddenOperationException.class, () -> service.getUsageByOrganizations(
                    Instant.now().minusSeconds(3600), Instant.now(), null, null, PageRequest.of(0, 50), principal));
        }
        verifyNoInteractions(repository, executor);
    }
}
