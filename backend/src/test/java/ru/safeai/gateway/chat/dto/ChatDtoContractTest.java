package ru.safeai.gateway.chat.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.SliceImpl;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatDtoContractTest {

    private static final ValidatorFactory VALIDATOR_FACTORY =
            Validation.buildDefaultValidatorFactory();

    private static final Validator VALIDATOR =
            VALIDATOR_FACTORY.getValidator();

    @AfterAll
    static void closeValidatorFactory() {
        VALIDATOR_FACTORY.close();
    }

    @Test
    void clientRequestIdIsMandatory() {
        var violations = VALIDATOR.validate(
                new SendMessageRequest(
                        "Hello",
                        null
                )
        );

        assertThat(violations)
                .extracting(violation ->
                        violation
                                .getPropertyPath()
                                .toString()
                )
                .contains("clientRequestId");
    }

    @Test
    void blankMessageIsRejected() {
        var request = new SendMessageRequest(
                "   ",
                UUID.randomUUID()
        );

        assertThat(
                VALIDATOR.validate(request)
        ).isNotEmpty();
    }

    @Test
    void messageOverAbsoluteApiLimitIsRejected() {
        var request = new SendMessageRequest(
                "x".repeat(100_001),
                UUID.randomUUID()
        );

        assertThat(
                VALIDATOR.validate(request)
        ).isNotEmpty();
    }

    @Test
    void pageRequestHasDeterministicIdTieBreaker() {
        var pageable = new ChatPageRequest(
                1,
                20,
                "updatedAt",
                "desc"
        ).toPageable(100);

        assertThat(pageable.getPageNumber())
                .isEqualTo(1);

        assertThat(
                pageable
                        .getSort()
                        .getOrderFor("updatedAt")
        ).isNotNull();

        assertThat(
                pageable
                        .getSort()
                        .getOrderFor("id")
        ).isNotNull();
    }

    @Test
    void configuredPageMaximumCannotBeBypassed() {
        var pageable = new ChatPageRequest(
                0,
                50,
                "id",
                "asc"
        ).toPageable(25);

        assertThat(pageable.getPageSize())
                .isEqualTo(25);
    }

    @Test
    void unsupportedSortIsRejectedEvenWithoutBeanValidation() {
        assertThatThrownBy(() ->
                new ChatPageRequest(
                        0,
                        20,
                        "user.passwordHash",
                        "asc"
                ).toPageable(100)
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining("сортировки");
    }

    @Test
    void messagePagingCannotAcceptClientSort() {
        var pageable = new MessagePageRequest(
                0,
                50
        ).toPageable(100);

        assertThat(
                pageable
                        .getSort()
                        .getOrderFor("createdAt")
        ).isNotNull();

        assertThat(
                pageable
                        .getSort()
                        .getOrderFor("id")
        ).isNotNull();
    }

    @Test
    void pageResponseRejectsNullContent() {
        assertThatThrownBy(() ->
                new ChatPageResponse<>(
                        null,
                        0,
                        20,
                        true,
                        true,
                        false,
                        false
                )
        ).isInstanceOf(NullPointerException.class);
    }

    @Test
    void pageResponseCopiesContent() {
        var response = ChatPageResponse.from(
                new SliceImpl<>(
                        List.of("a", "b")
                )
        );

        assertThat(response.content())
                .containsExactly("a", "b");

        assertThatThrownBy(() ->
                response.content().add("c")
        ).isInstanceOf(
                UnsupportedOperationException.class
        );
    }
}