package ru.safeai.gateway.knowledge.controller;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ru.safeai.gateway.knowledge.dto.CreateKnowledgeBaseMemberRequest;
import ru.safeai.gateway.knowledge.dto.CreateKnowledgeBaseRequest;
import ru.safeai.gateway.knowledge.dto.UpdateKnowledgeBaseMemberRequest;
import ru.safeai.gateway.knowledge.dto.UpdateKnowledgeBaseRequest;
import ru.safeai.gateway.knowledge.model.KnowledgeBaseAccessLevel;
import ru.safeai.gateway.knowledge.model.KnowledgeBaseVisibility;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeDtoValidationTest {

    private static final int MAX_DESCRIPTION_LENGTH =
            2_000;

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory =
                Validation.buildDefaultValidatorFactory();

        validator =
                factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        if (factory != null) {
            factory.close();
        }
    }

    @Test
    void createKnowledgeBaseRequest_validPayloadHasNoViolations() {
        CreateKnowledgeBaseRequest request =
                new CreateKnowledgeBaseRequest(
                        "Production Runbooks",
                        "Описание",
                        KnowledgeBaseVisibility.MEMBERS
                );

        assertThat(
                validator.validate(request)
        ).isEmpty();
    }

    @Test
    void createKnowledgeBaseRequest_acceptsMaximumDescriptionLength() {
        CreateKnowledgeBaseRequest request =
                new CreateKnowledgeBaseRequest(
                        "Production Runbooks",
                        "x".repeat(
                                MAX_DESCRIPTION_LENGTH
                        ),
                        KnowledgeBaseVisibility.MEMBERS
                );

        assertThat(
                validator.validate(request)
        ).isEmpty();
    }

    @Test
    void createKnowledgeBaseRequest_rejectsBlankNameNullVisibilityAndLongDescription() {
        CreateKnowledgeBaseRequest request =
                new CreateKnowledgeBaseRequest(
                        " ",
                        "x".repeat(
                                MAX_DESCRIPTION_LENGTH + 1
                        ),
                        null
                );

        var violations =
                validator.validate(
                        request
                );

        assertThat(
                violations
        ).hasSize(
                3
        );

        assertThat(
                violations
        )
                .extracting(
                        violation ->
                                violation
                                        .getPropertyPath()
                                        .toString()
                )
                .containsExactlyInAnyOrder(
                        "name",
                        "description",
                        "visibility"
                );
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    void updateKnowledgeBaseRequest_rejectsNegativeExpectedVersion() {
        UpdateKnowledgeBaseRequest request =
                new UpdateKnowledgeBaseRequest(
                        "KB",
                        null,
                        KnowledgeBaseVisibility.ORGANIZATION,
                        true,
                        -1L
                );

        var violations =
                validator.validate(
                        request
                );

        assertThat(
                violations
        ).hasSize(
                1
        );

        assertThat(
                violations
        )
                .allSatisfy(
                        violation ->
                                assertThat(
                                        violation
                                                .getPropertyPath()
                                                .toString()
                                ).isEqualTo(
                                        "expectedVersion"
                                )
                );
    }

    @Test
    void createMembership_rejectsMissingUserAndAccessLevel() {
        CreateKnowledgeBaseMemberRequest request =
                new CreateKnowledgeBaseMemberRequest(
                        null,
                        null
                );

        var violations =
                validator.validate(
                        request
                );

        assertThat(
                violations
        ).hasSize(
                2
        );

        assertThat(
                violations
        )
                .extracting(
                        violation ->
                                violation
                                        .getPropertyPath()
                                        .toString()
                )
                .containsExactlyInAnyOrder(
                        "userId",
                        "accessLevel"
                );
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    void updateMembership_rejectsNullAccessAndNegativeVersion() {
        UpdateKnowledgeBaseMemberRequest request =
                new UpdateKnowledgeBaseMemberRequest(
                        null,
                        -1L
                );

        var violations =
                validator.validate(
                        request
                );

        assertThat(
                violations
        ).hasSize(
                2
        );

        assertThat(
                violations
        )
                .extracting(
                        violation ->
                                violation
                                        .getPropertyPath()
                                        .toString()
                )
                .containsExactlyInAnyOrder(
                        "accessLevel",
                        "expectedVersion"
                );
    }

    @Test
    void updateMembership_acceptsZeroVersion() {
        UpdateKnowledgeBaseMemberRequest request =
                new UpdateKnowledgeBaseMemberRequest(
                        KnowledgeBaseAccessLevel.EDITOR,
                        0L
                );

        assertThat(
                validator.validate(request)
        ).isEmpty();
    }
}