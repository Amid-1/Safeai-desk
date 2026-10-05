package ru.safeai.gateway.knowledge.controller;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ru.safeai.gateway.knowledge.dto.CreateKnowledgeBaseMemberRequest;
import ru.safeai.gateway.knowledge.dto.CreateKnowledgeBaseRequest;
import ru.safeai.gateway.knowledge.dto.KnowledgeEvaluationCaseRequest;
import ru.safeai.gateway.knowledge.dto.KnowledgeEvaluationRequest;
import ru.safeai.gateway.knowledge.dto.KnowledgeRetrievalRequest;
import ru.safeai.gateway.knowledge.dto.UpdateKnowledgeBaseMemberRequest;
import ru.safeai.gateway.knowledge.dto.UpdateKnowledgeBaseRequest;
import ru.safeai.gateway.knowledge.dto.UpdateKnowledgeDocumentRequest;
import ru.safeai.gateway.knowledge.model.KnowledgeBaseAccessLevel;
import ru.safeai.gateway.knowledge.model.KnowledgeBaseVisibility;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeDtoValidationTest {

    private static final int MAX_DESCRIPTION_LENGTH =
            2_000;

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory =
                Validation
                        .buildDefaultValidatorFactory();

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
                validator.validate(
                        request
                )
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
                validator.validate(
                        request
                )
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
                validator.validate(
                        request
                )
        ).isEmpty();
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    void updateKnowledgeDocument_rejectsBlankNameNullEnabledAndNegativeVersion() {
        UpdateKnowledgeDocumentRequest request =
                new UpdateKnowledgeDocumentRequest(
                        " ",
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
                        "enabled",
                        "expectedVersion"
                );
    }

    @Test
    void retrievalRequest_rejectsBlankQuery() {
        KnowledgeRetrievalRequest request =
                new KnowledgeRetrievalRequest(
                        " ",
                        1
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
                .extracting(
                        violation ->
                                violation
                                        .getPropertyPath()
                                        .toString()
                )
                .containsExactly(
                        "query"
                );
    }

    @Test
    void retrievalRequest_rejectsTopKAboveBoundaryWithoutConstructingInvalidRecord() {
        var violations =
                validator.validateValue(
                        KnowledgeRetrievalRequest.class,
                        "topK",
                        101
                );

        assertThat(
                violations
        ).hasSize(
                1
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
                .containsExactly(
                        "topK"
                );
    }

    @Test
    void retrievalRequest_acceptsInclusiveTopKBoundaries() {
        assertThat(
                validator.validateValue(
                        KnowledgeRetrievalRequest.class,
                        "topK",
                        1
                )
        ).isEmpty();

        assertThat(
                validator.validateValue(
                        KnowledgeRetrievalRequest.class,
                        "topK",
                        100
                )
        ).isEmpty();
    }

    @Test
    void evaluationRequest_rejectsBlankDatasetAndEmptyCases() {
        KnowledgeEvaluationRequest request =
                new KnowledgeEvaluationRequest(
                        " ",
                        1,
                        List.of()
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
                        "datasetName",
                        "cases"
                );
    }

    @Test
    void evaluationRequest_rejectsTopKBelowBoundaryWithoutConstructingInvalidRecord() {
        var violations =
                validator.validateValue(
                        KnowledgeEvaluationRequest.class,
                        "topK",
                        0
                );

        assertThat(
                violations
        ).hasSize(
                1
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
                .containsExactly(
                        "topK"
                );
    }

    @Test
    void evaluationRequest_rejectsTopKAboveBoundaryWithoutConstructingInvalidRecord() {
        var violations =
                validator.validateValue(
                        KnowledgeEvaluationRequest.class,
                        "topK",
                        21
                );

        assertThat(
                violations
        ).hasSize(
                1
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
                .containsExactly(
                        "topK"
                );
    }

    @Test
    void evaluationRequest_acceptsInclusiveTopKBoundaries() {
        assertThat(
                validator.validateValue(
                        KnowledgeEvaluationRequest.class,
                        "topK",
                        1
                )
        ).isEmpty();

        assertThat(
                validator.validateValue(
                        KnowledgeEvaluationRequest.class,
                        "topK",
                        20
                )
        ).isEmpty();
    }

    @Test
    void evaluationRequest_cascadesValidationIntoCases() {
        KnowledgeEvaluationRequest request =
                new KnowledgeEvaluationRequest(
                        "Dataset",
                        5,
                        List.of(
                                new KnowledgeEvaluationCaseRequest(
                                        " ",
                                        Set.of()
                                )
                        )
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
                        "cases[0].query",
                        "cases[0].expectedDocumentVersionIds"
                );
    }

    @Test
    void evaluationRequest_acceptsValidNestedCase() {
        KnowledgeEvaluationRequest request =
                new KnowledgeEvaluationRequest(
                        "Dataset",
                        5,
                        List.of(
                                new KnowledgeEvaluationCaseRequest(
                                        "What is the approved policy?",
                                        Set.of(
                                                UUID.fromString(
                                                        "aaaaaaaa-0000-0000-0000-000000000001"
                                                )
                                        )
                                )
                        )
                );

        assertThat(
                validator.validate(
                        request
                )
        ).isEmpty();
    }
}
