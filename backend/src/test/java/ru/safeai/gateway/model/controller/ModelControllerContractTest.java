package ru.safeai.gateway.model.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.model.dto.CreateModelCatalogVersionRequest;
import ru.safeai.gateway.model.dto.RuntimeModelStatusResponse;
import ru.safeai.gateway.model.service.ModelCatalogService;
import ru.safeai.gateway.model.service.ModelRoutingService;
import ru.safeai.gateway.model.service.OrganizationModelPolicyService;
import ru.safeai.gateway.model.service.RuntimeModelStatusService;
import ru.safeai.gateway.model.testsupport.ModelTestFixtures;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelControllerContractTest {

    private static final String ADMINISTRATIVE_READ_BOUNDARY =
            "hasAnyRole('ADMIN', 'SUPER_ADMIN')";

    private static final String SUPER_ADMIN_WRITE_BOUNDARY =
            "hasRole('SUPER_ADMIN')";

    @Test
    void catalogControllerKeepsAdminReadAndSuperAdminWriteBoundary()
            throws NoSuchMethodException {
        assertClassMapping(
                ModelCatalogController.class,
                "/api/admin/models/catalog"
        );
        assertAdministrativeClassBoundary(
                ModelCatalogController.class
        );

        Method create = ModelCatalogController.class.getMethod(
                "createVersion",
                CreateModelCatalogVersionRequest.class,
                SafeAiUserPrincipal.class
        );
        Method importRuntime = ModelCatalogController.class.getMethod(
                "importRuntime",
                SafeAiUserPrincipal.class
        );

        assertThat(
                requirePreAuthorize(create).value()
        ).isEqualTo(
                SUPER_ADMIN_WRITE_BOUNDARY
        );

        assertThat(
                requirePreAuthorize(importRuntime).value()
        ).isEqualTo(
                SUPER_ADMIN_WRITE_BOUNDARY
        );
    }

    @Test
    void routeEvidenceControllerKeepsAdministrativeBoundary() {
        assertClassMapping(
                ModelRouteDecisionController.class,
                "/api/admin/models/route-decisions"
        );
        assertAdministrativeClassBoundary(
                ModelRouteDecisionController.class
        );
    }

    @Test
    void organizationPolicyControllerKeepsAdministrativeBoundary() {
        assertClassMapping(
                OrganizationModelPolicyController.class,
                "/api/admin/models/policies"
        );
        assertAdministrativeClassBoundary(
                OrganizationModelPolicyController.class
        );
    }

    @Test
    void runtimeEndpointRequiresAdministrativeRole()
            throws NoSuchMethodException {
        assertClassMapping(
                ModelRuntimeController.class,
                "/api/admin/models"
        );

        Method runtime = ModelRuntimeController.class.getMethod(
                "runtime"
        );

        assertThat(
                requirePreAuthorize(runtime).value()
        ).isEqualTo(
                ADMINISTRATIVE_READ_BOUNDARY
        );
    }

    @Test
    void catalogControllerDelegatesWithoutReimplementingBusinessRules() {
        ModelCatalogService service =
                mock(ModelCatalogService.class);
        ModelCatalogController controller =
                new ModelCatalogController(service);

        when(service.findLatest(
                ModelTestFixtures.adminPrincipal()
        )).thenReturn(
                List.of()
        );

        assertThat(controller.latest(
                ModelTestFixtures.adminPrincipal()
        )).isEmpty();

        verify(service).findLatest(
                ModelTestFixtures.adminPrincipal()
        );
    }

    @Test
    void runtimeControllerReturnsSanitizedRuntimeReadModelOnly() {
        RuntimeModelStatusService service =
                mock(RuntimeModelStatusService.class);

        RuntimeModelStatusResponse expected =
                new RuntimeModelStatusResponse(
                        "openai",
                        "gpt-test",
                        true,
                        "SINGLE_PROVIDER_STATIC",
                        32_000,
                        4_096,
                        false,
                        false,
                        false,
                        "NOT_DECLARED",
                        "NOT_PROBED",
                        "FREE",
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null
                );

        when(service.current())
                .thenReturn(expected);

        ModelRuntimeController controller =
                new ModelRuntimeController(service);

        assertThat(controller.runtime())
                .isSameAs(expected);
    }

    @Test
    void routeDecisionControllerDelegatesExactEvidenceIdentity() {
        ModelRoutingService service =
                mock(ModelRoutingService.class);
        ModelRouteDecisionController controller =
                new ModelRouteDecisionController(service);
        UUID decisionId = UUID.randomUUID();

        controller.findById(
                decisionId,
                ModelTestFixtures.adminPrincipal()
        );

        verify(service).findDecision(
                decisionId,
                ModelTestFixtures.adminPrincipal()
        );
    }

    @Test
    void policyControllerDelegatesTenantIdentityToServiceLayer() {
        OrganizationModelPolicyService service =
                mock(OrganizationModelPolicyService.class);
        OrganizationModelPolicyController controller =
                new OrganizationModelPolicyController(service);

        controller.current(
                ModelTestFixtures.ORGANIZATION_ID,
                ModelTestFixtures.adminPrincipal()
        );

        verify(service).current(
                ModelTestFixtures.ORGANIZATION_ID,
                ModelTestFixtures.adminPrincipal()
        );
    }

    private static void assertClassMapping(
            Class<?> type,
            String expected
    ) {
        RequestMapping mapping = Objects.requireNonNull(
                type.getAnnotation(
                        RequestMapping.class
                ),
                () -> type.getName()
                        + " must declare @RequestMapping"
        );

        assertThat(mapping.value())
                .containsExactly(expected);
    }

    private static void assertAdministrativeClassBoundary(
            Class<?> type
    ) {
        assertThat(
                requirePreAuthorize(type).value()
        ).isEqualTo(
                ADMINISTRATIVE_READ_BOUNDARY
        );
    }

    private static PreAuthorize requirePreAuthorize(
            AnnotatedElement element
    ) {
        return Objects.requireNonNull(
                element.getAnnotation(
                        PreAuthorize.class
                ),
                () -> element
                        + " must declare @PreAuthorize"
        );
    }
}
