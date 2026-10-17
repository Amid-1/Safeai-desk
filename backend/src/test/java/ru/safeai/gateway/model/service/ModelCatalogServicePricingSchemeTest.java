package ru.safeai.gateway.model.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.safeai.gateway.ai.pricing.ModelPricingScheme;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.model.domain.*;
import ru.safeai.gateway.model.dto.CreateModelCatalogVersionRequest;
import ru.safeai.gateway.model.repository.ModelCatalogRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelCatalogServicePricingSchemeTest {
    private final ModelCatalogRepository repo = mock(ModelCatalogRepository.class);
    private final AuditEventService audit = mock(AuditEventService.class);
    private final RuntimeModelStatusService runtime = mock(RuntimeModelStatusService.class);
    private final ModelCatalogService service = new ModelCatalogService(
            repo, runtime, audit,
            Clock.fixed(Instant.parse("2026-10-17T13:00:00Z"), ZoneOffset.UTC));

    @Test
    void configuredWithoutExplicitVerifiedSchemeFailsBeforeInsert() {
        assertThatThrownBy(() -> service.createVersion(request(null), admin()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("pricingScheme");
        verify(repo, never()).insert(any(ModelCatalogEntry.class));
    }

    @Test
    void explicitVerifiedSchemePersistsInImmutableCatalogSnapshot() {
        when(repo.findLatest("openai:test")).thenReturn(Optional.empty());
        var response = service.createVersion(request(ModelPricingScheme.FLAT_TOKEN_V1), admin());
        assertThat(response.pricingScheme()).isEqualTo(ModelPricingScheme.FLAT_TOKEN_V1);
        ArgumentCaptor<ModelCatalogEntry> entry = ArgumentCaptor.forClass(ModelCatalogEntry.class);
        verify(repo).insert(entry.capture());
        assertThat(entry.getValue().pricingScheme())
                .isEqualTo(ModelPricingScheme.FLAT_TOKEN_V1);
    }

    private static SafeAiUserPrincipal admin() {
        return SafeAiUserPrincipal.accessTokenPrincipal(
                UUID.randomUUID(), UUID.randomUUID(), 0L, 0L,
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")));
    }

    private static CreateModelCatalogVersionRequest request(ModelPricingScheme scheme) {
        return new CreateModelCatalogVersionRequest(
                "openai:test", "openai", "test", "Test",
                ModelLifecycle.ACTIVE, 10000, 2000,
                Set.of(), Set.of(ModelModality.TEXT), Set.of(ModelModality.TEXT),
                ModelRetentionStatus.NOT_DECLARED, null,
                ModelTrainingUseStatus.NOT_DECLARED,
                ModelPricingStatus.CONFIGURED, true,
                new BigDecimal("1"), null, null, new BigDecimal("2"),
                "{}", "manual-2026", null, 0, scheme);
    }
}
