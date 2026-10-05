package ru.safeai.gateway.model.controller;

import jakarta.validation.Valid;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.model.dto.CreateOrganizationModelPolicyVersionRequest;
import ru.safeai.gateway.model.dto.ModelPolicyPreviewResponse;
import ru.safeai.gateway.model.service.ModelPolicyPreviewService;
import ru.safeai.gateway.model.testsupport.ModelTestFixtures;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelPolicyPreviewControllerTest {

    @Test
    void previewDelegatesExactTenantDraftAndPrincipal() {
        ModelPolicyPreviewService service = mock(ModelPolicyPreviewService.class);
        ModelPolicyPreviewController controller = new ModelPolicyPreviewController(service);
        UUID organizationId = ModelTestFixtures.ORGANIZATION_ID;
        CreateOrganizationModelPolicyVersionRequest request =
                mock(CreateOrganizationModelPolicyVersionRequest.class);
        SafeAiUserPrincipal principal = ModelTestFixtures.adminPrincipal();
        ModelPolicyPreviewResponse expected = mock(ModelPolicyPreviewResponse.class);

        when(service.preview(organizationId, request, principal)).thenReturn(expected);

        assertThat(controller.preview(organizationId, request, principal)).isSameAs(expected);
        verify(service).preview(
                same(organizationId),
                same(request),
                same(principal)
        );
    }

    @Test
    void previewContractKeepsAdministrativeBoundaryAndValidationAnnotations() throws Exception {
        RequestMapping classMapping = ModelPolicyPreviewController.class
                .getAnnotation(RequestMapping.class);
        PreAuthorize authorization = ModelPolicyPreviewController.class
                .getAnnotation(PreAuthorize.class);

        assertThat(classMapping).isNotNull();
        assertThat(classMapping.value()).containsExactly("/api/admin/models/policies");
        assertThat(authorization).isNotNull();
        assertThat(authorization.value()).isEqualTo("hasAnyRole('ADMIN', 'SUPER_ADMIN')");

        Method method = ModelPolicyPreviewController.class.getMethod(
                "preview",
                UUID.class,
                CreateOrganizationModelPolicyVersionRequest.class,
                SafeAiUserPrincipal.class
        );
        PostMapping mapping = method.getAnnotation(PostMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).containsExactly("/{organizationId}/preview");

        Parameter[] parameters = method.getParameters();
        assertThat(parameters[0].getAnnotation(PathVariable.class)).isNotNull();
        assertThat(parameters[1].getAnnotation(RequestBody.class)).isNotNull();
        assertThat(parameters[1].getAnnotation(Valid.class)).isNotNull();

        AuthenticationPrincipal principal =
                parameters[2].getAnnotation(AuthenticationPrincipal.class);
        assertThat(principal).isNotNull();
        assertThat(principal.errorOnInvalidType()).isTrue();
    }

    @Test
    void constructorRejectsNullService() {
        assertThatThrownBy(() -> new ModelPolicyPreviewController(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("service");
    }
}
