package ru.safeai.gateway.model.controller;

import jakarta.validation.Valid;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.model.dto.CreateModelCatalogVersionRequest;
import ru.safeai.gateway.model.dto.CreateOrganizationModelPolicyVersionRequest;
import ru.safeai.gateway.model.dto.ModelCatalogEntryResponse;
import ru.safeai.gateway.model.dto.ModelRouteDecisionResponse;
import ru.safeai.gateway.model.dto.OrganizationModelPolicyResponse;
import ru.safeai.gateway.model.dto.RuntimeModelStatusResponse;
import ru.safeai.gateway.model.dto.RuntimeModelStatusWireResponse;
import ru.safeai.gateway.model.service.ModelCatalogService;
import ru.safeai.gateway.model.service.ModelRoutingService;
import ru.safeai.gateway.model.service.OrganizationModelPolicyService;
import ru.safeai.gateway.model.service.RuntimeModelStatusService;
import ru.safeai.gateway.model.testsupport.ModelTestFixtures;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelControllerContractTest {

    private static final String ADMINISTRATIVE_READ_BOUNDARY =
            "hasAnyRole('ADMIN', 'SUPER_ADMIN')";

    private static final String SUPER_ADMIN_WRITE_BOUNDARY =
            "hasRole('SUPER_ADMIN')";

    @Test
    void catalogControllerKeepsMappingsAndAuthorizationBoundaries()
            throws NoSuchMethodException {

        assertClassMapping(
                ModelCatalogController.class,
                "/api/admin/models/catalog"
        );

        assertAdministrativeClassBoundary(
                ModelCatalogController.class
        );

        Method latest =
                ModelCatalogController.class.getMethod(
                        "latest",
                        SafeAiUserPrincipal.class
                );

        Method effective =
                ModelCatalogController.class.getMethod(
                        "effective",
                        SafeAiUserPrincipal.class
                );

        Method createVersion =
                ModelCatalogController.class.getMethod(
                        "createVersion",
                        CreateModelCatalogVersionRequest.class,
                        SafeAiUserPrincipal.class
                );

        Method importRuntime =
                ModelCatalogController.class.getMethod(
                        "importRuntime",
                        SafeAiUserPrincipal.class
                );

        assertGetMapping(
                latest,
                ""
        );

        assertGetMapping(
                effective,
                "/effective"
        );

        assertPostMapping(
                createVersion,
                ""
        );

        assertPostMapping(
                importRuntime,
                "/import-runtime"
        );

        assertNoMethodPreAuthorize(
                latest
        );

        assertNoMethodPreAuthorize(
                effective
        );

        assertThat(
                requirePreAuthorize(
                        createVersion
                ).value()
        ).isEqualTo(
                SUPER_ADMIN_WRITE_BOUNDARY
        );

        assertThat(
                requirePreAuthorize(
                        importRuntime
                ).value()
        ).isEqualTo(
                SUPER_ADMIN_WRITE_BOUNDARY
        );

        assertAuthenticationPrincipal(
                latest.getParameters()[0]
        );

        assertAuthenticationPrincipal(
                effective.getParameters()[0]
        );

        assertRequestBodyValidated(
                createVersion.getParameters()[0]
        );

        assertAuthenticationPrincipal(
                createVersion.getParameters()[1]
        );

        assertAuthenticationPrincipal(
                importRuntime.getParameters()[0]
        );
    }

    @Test
    void routeEvidenceControllerKeepsMappingAndAdministrativeBoundary()
            throws NoSuchMethodException {

        assertClassMapping(
                ModelRouteDecisionController.class,
                "/api/admin/models/route-decisions"
        );

        assertAdministrativeClassBoundary(
                ModelRouteDecisionController.class
        );

        Method findById =
                ModelRouteDecisionController.class.getMethod(
                        "findById",
                        UUID.class,
                        SafeAiUserPrincipal.class
                );

        assertGetMapping(
                findById,
                "/{decisionId}"
        );

        assertNoMethodPreAuthorize(
                findById
        );

        assertPathVariable(
                findById.getParameters()[0]
        );

        assertAuthenticationPrincipal(
                findById.getParameters()[1]
        );
    }

    @Test
    void organizationPolicyControllerKeepsMappingsAndAdministrativeBoundary()
            throws NoSuchMethodException {

        assertClassMapping(
                OrganizationModelPolicyController.class,
                "/api/admin/models/policies"
        );

        assertAdministrativeClassBoundary(
                OrganizationModelPolicyController.class
        );

        Method current =
                OrganizationModelPolicyController.class.getMethod(
                        "current",
                        UUID.class,
                        SafeAiUserPrincipal.class
                );

        Method createVersion =
                OrganizationModelPolicyController.class.getMethod(
                        "createVersion",
                        UUID.class,
                        CreateOrganizationModelPolicyVersionRequest.class,
                        SafeAiUserPrincipal.class
                );

        assertGetMapping(
                current,
                "/{organizationId}"
        );

        assertPostMapping(
                createVersion,
                "/{organizationId}"
        );

        assertNoMethodPreAuthorize(
                current
        );

        assertNoMethodPreAuthorize(
                createVersion
        );

        assertPathVariable(
                current.getParameters()[0]
        );

        assertAuthenticationPrincipal(
                current.getParameters()[1]
        );

        assertPathVariable(
                createVersion.getParameters()[0]
        );

        assertRequestBodyValidated(
                createVersion.getParameters()[1]
        );

        assertAuthenticationPrincipal(
                createVersion.getParameters()[2]
        );
    }

    @Test
    void runtimeEndpointKeepsMappingAndAdministrativeBoundary()
            throws NoSuchMethodException {

        assertClassMapping(
                ModelRuntimeController.class,
                "/api/admin/models"
        );

        Method runtime =
                ModelRuntimeController.class.getMethod(
                        "runtime"
                );

        assertGetMapping(
                runtime,
                "/runtime"
        );

        assertThat(
                requirePreAuthorize(
                        runtime
                ).value()
        ).isEqualTo(
                ADMINISTRATIVE_READ_BOUNDARY
        );
    }

    @Test
    void catalogControllerDelegatesLatestAndEffectiveWithExactPrincipal() {
        ModelCatalogService service =
                mock(
                        ModelCatalogService.class
                );

        ModelCatalogController controller =
                new ModelCatalogController(
                        service
                );

        SafeAiUserPrincipal principal =
                ModelTestFixtures.adminPrincipal();

        List<ModelCatalogEntryResponse> latest =
                List.of();

        List<ModelCatalogEntryResponse> effective =
                List.of();

        when(
                service.findLatest(
                        principal
                )
        ).thenReturn(
                latest
        );

        when(
                service.findEffective(
                        principal
                )
        ).thenReturn(
                effective
        );

        assertThat(
                controller.latest(
                        principal
                )
        ).isSameAs(
                latest
        );

        assertThat(
                controller.effective(
                        principal
                )
        ).isSameAs(
                effective
        );

        verify(
                service
        ).findLatest(
                same(
                        principal
                )
        );

        verify(
                service
        ).findEffective(
                same(
                        principal
                )
        );
    }

    @Test
    void catalogControllerDelegatesWritesWithExactArguments() {
        ModelCatalogService service =
                mock(
                        ModelCatalogService.class
                );

        ModelCatalogController controller =
                new ModelCatalogController(
                        service
                );

        SafeAiUserPrincipal principal =
                ModelTestFixtures.superAdminPrincipal();

        CreateModelCatalogVersionRequest request =
                mock(
                        CreateModelCatalogVersionRequest.class
                );

        ModelCatalogEntryResponse created =
                mock(
                        ModelCatalogEntryResponse.class
                );

        ModelCatalogEntryResponse imported =
                mock(
                        ModelCatalogEntryResponse.class
                );

        when(
                service.createVersion(
                        request,
                        principal
                )
        ).thenReturn(
                created
        );

        when(
                service.importRuntime(
                        principal
                )
        ).thenReturn(
                imported
        );

        assertThat(
                controller.createVersion(
                        request,
                        principal
                )
        ).isSameAs(
                created
        );

        assertThat(
                controller.importRuntime(
                        principal
                )
        ).isSameAs(
                imported
        );

        verify(
                service
        ).createVersion(
                same(
                        request
                ),
                same(
                        principal
                )
        );

        verify(
                service
        ).importRuntime(
                same(
                        principal
                )
        );
    }

    @Test
    void runtimeControllerReturnsSanitizedWireReadModel() {
        RuntimeModelStatusService service =
                mock(
                        RuntimeModelStatusService.class
                );

        RuntimeModelStatusResponse runtime =
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

        RuntimeModelStatusWireResponse expected =
                RuntimeModelStatusWireResponse.from(
                        runtime
                );

        when(
                service.current()
        ).thenReturn(
                runtime
        );

        ModelRuntimeController controller =
                new ModelRuntimeController(
                        service
                );

        RuntimeModelStatusWireResponse actual =
                controller.runtime();

        assertThat(
                actual
        ).isEqualTo(
                expected
        );

        assertThat(
                actual.provider()
        ).isEqualTo(
                "openai"
        );

        assertThat(
                actual.model()
        ).isEqualTo(
                "gpt-test"
        );

        assertThat(
                actual.maxInputTokens()
        ).isEqualTo(
                32_000
        );

        assertThat(
                actual.maxOutputTokens()
        ).isEqualTo(
                4_096
        );

        verify(
                service
        ).current();
    }

    @Test
    void routeDecisionControllerDelegatesExactEvidenceIdentity() {
        ModelRoutingService service =
                mock(
                        ModelRoutingService.class
                );

        ModelRouteDecisionController controller =
                new ModelRouteDecisionController(
                        service
                );

        UUID decisionId =
                UUID.randomUUID();

        SafeAiUserPrincipal principal =
                ModelTestFixtures.adminPrincipal();

        ModelRouteDecisionResponse expected =
                mock(
                        ModelRouteDecisionResponse.class
                );

        when(
                service.findDecision(
                        decisionId,
                        principal
                )
        ).thenReturn(
                expected
        );

        assertThat(
                controller.findById(
                        decisionId,
                        principal
                )
        ).isSameAs(
                expected
        );

        verify(
                service
        ).findDecision(
                eq(
                        decisionId
                ),
                same(
                        principal
                )
        );
    }

    @Test
    void policyControllerDelegatesCurrentAndCreateVersionWithExactArguments() {
        OrganizationModelPolicyService service =
                mock(
                        OrganizationModelPolicyService.class
                );

        OrganizationModelPolicyController controller =
                new OrganizationModelPolicyController(
                        service
                );

        UUID organizationId =
                ModelTestFixtures.ORGANIZATION_ID;

        SafeAiUserPrincipal principal =
                ModelTestFixtures.adminPrincipal();

        OrganizationModelPolicyResponse current =
                mock(
                        OrganizationModelPolicyResponse.class
                );

        CreateOrganizationModelPolicyVersionRequest request =
                mock(
                        CreateOrganizationModelPolicyVersionRequest.class
                );

        OrganizationModelPolicyResponse created =
                mock(
                        OrganizationModelPolicyResponse.class
                );

        when(
                service.current(
                        organizationId,
                        principal
                )
        ).thenReturn(
                current
        );

        when(
                service.createVersion(
                        organizationId,
                        request,
                        principal
                )
        ).thenReturn(
                created
        );

        assertThat(
                controller.current(
                        organizationId,
                        principal
                )
        ).isSameAs(
                current
        );

        assertThat(
                controller.createVersion(
                        organizationId,
                        request,
                        principal
                )
        ).isSameAs(
                created
        );

        verify(
                service
        ).current(
                eq(
                        organizationId
                ),
                same(
                        principal
                )
        );

        verify(
                service
        ).createVersion(
                eq(
                        organizationId
                ),
                same(
                        request
                ),
                same(
                        principal
                )
        );
    }

    @Test
    void controllerConstructorsRejectNullServices() {
        assertThatThrownBy(
                () ->
                        new ModelCatalogController(
                                null
                        )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessageContaining(
                        "service"
                );

        assertThatThrownBy(
                () ->
                        new ModelRouteDecisionController(
                                null
                        )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessageContaining(
                        "service"
                );

        assertThatThrownBy(
                () ->
                        new ModelRuntimeController(
                                null
                        )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessageContaining(
                        "statusService"
                );

        assertThatThrownBy(
                () ->
                        new OrganizationModelPolicyController(
                                null
                        )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessageContaining(
                        "service"
                );
    }

    private static void assertClassMapping(
            Class<?> type,
            String expected
    ) {
        RequestMapping mapping =
                Objects.requireNonNull(
                        type.getAnnotation(
                                RequestMapping.class
                        ),
                        () ->
                                type.getName()
                                        + " must declare @RequestMapping"
                );

        assertThat(
                resolveMappingPath(
                        mapping.value(),
                        mapping.path()
                )
        ).containsExactly(
                expected
        );
    }

    private static void assertGetMapping(
            Method method,
            String expected
    ) {
        GetMapping mapping =
                Objects.requireNonNull(
                        method.getAnnotation(
                                GetMapping.class
                        ),
                        () ->
                                method
                                        + " must declare @GetMapping"
                );

        assertThat(
                resolveMappingPath(
                        mapping.value(),
                        mapping.path()
                )
        ).containsExactly(
                expected
        );
    }

    private static void assertPostMapping(
            Method method,
            String expected
    ) {
        PostMapping mapping =
                Objects.requireNonNull(
                        method.getAnnotation(
                                PostMapping.class
                        ),
                        () ->
                                method
                                        + " must declare @PostMapping"
                );

        assertThat(
                resolveMappingPath(
                        mapping.value(),
                        mapping.path()
                )
        ).containsExactly(
                expected
        );
    }

    private static List<String> resolveMappingPath(
            String[] value,
            String[] path
    ) {
        String[] mapping =
                value.length > 0
                        ? value
                        : path;

        return mapping.length == 0
                ? List.of("")
                : List.of(mapping);
    }

    private static void assertAdministrativeClassBoundary(
            Class<?> type
    ) {
        assertThat(
                requirePreAuthorize(
                        type
                ).value()
        ).isEqualTo(
                ADMINISTRATIVE_READ_BOUNDARY
        );
    }

    private static void assertNoMethodPreAuthorize(
            Method method
    ) {
        assertThat(
                method.getAnnotation(
                        PreAuthorize.class
                )
        ).as(
                "%s must inherit its class-level authorization boundary",
                method
        ).isNull();
    }

    private static PreAuthorize requirePreAuthorize(
            AnnotatedElement element
    ) {
        return Objects.requireNonNull(
                element.getAnnotation(
                        PreAuthorize.class
                ),
                () ->
                        element
                                + " must declare @PreAuthorize"
        );
    }

    private static void assertAuthenticationPrincipal(
            Parameter parameter
    ) {
        AuthenticationPrincipal annotation =
                Objects.requireNonNull(
                        parameter.getAnnotation(
                                AuthenticationPrincipal.class
                        ),
                        () ->
                                parameter
                                        + " must declare @AuthenticationPrincipal"
                );

        assertThat(
                annotation.errorOnInvalidType()
        ).isTrue();
    }

    private static void assertPathVariable(
            Parameter parameter
    ) {
        assertThat(
                parameter.getAnnotation(
                        PathVariable.class
                )
        ).as(
                "%s must declare @PathVariable",
                parameter
        ).isNotNull();
    }

    private static void assertRequestBodyValidated(
            Parameter parameter
    ) {
        assertThat(
                parameter.getAnnotation(
                        RequestBody.class
                )
        ).as(
                "%s must declare @RequestBody",
                parameter
        ).isNotNull();

        assertThat(
                parameter.getAnnotation(
                        Valid.class
                )
        ).as(
                "%s must declare @Valid",
                parameter
        ).isNotNull();
    }
}
