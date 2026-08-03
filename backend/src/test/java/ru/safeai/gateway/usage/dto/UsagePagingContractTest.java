package ru.safeai.gateway.usage.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UsagePagingContractTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validatorFactory =
                Validation.buildDefaultValidatorFactory();

        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void defaultRequestUsesBoundedUnsortedPage() {
        UsagePageRequest request =
                new UsagePageRequest(null, null);

        Pageable pageable = request.toPageable();

        assertThat(pageable.getPageNumber())
                .isZero();

        assertThat(pageable.getPageSize())
                .isEqualTo(50);

        assertThat(pageable.getSort().isUnsorted())
                .isTrue();
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    void beanValidationRejectsNegativePageAndOversizedPage() {
        UsagePageRequest negativePage =
                new UsagePageRequest(-1, 50);

        UsagePageRequest oversizedPage =
                new UsagePageRequest(0, 201);

        assertThat(validator.validate(negativePage))
                .extracting(violation ->
                        violation
                                .getPropertyPath()
                                .toString()
                )
                .contains("page");

        assertThat(validator.validate(oversizedPage))
                .extracting(violation ->
                        violation
                                .getPropertyPath()
                                .toString()
                )
                .contains("size");
    }

    @Test
    void pagedResponseCopiesContentAndMapsSliceFlags() {
        List<String> mutable =
                new ArrayList<>(
                        List.of("a", "b")
                );

        SliceImpl<String> slice =
                new SliceImpl<>(
                        mutable,
                        PageRequest.of(1, 2),
                        true
                );

        PagedResponse<String> response =
                PagedResponse.from(slice);

        mutable.clear();

        assertThat(response.content())
                .containsExactly("a", "b");

        assertThat(response.page())
                .isEqualTo(1);

        assertThat(response.size())
                .isEqualTo(2);

        assertThat(response.first())
                .isFalse();

        assertThat(response.last())
                .isFalse();

        assertThat(response.hasNext())
                .isTrue();

        assertThat(response.hasPrevious())
                .isTrue();

        assertThatThrownBy(() ->
                response.content().add("c")
        ).isInstanceOf(
                UnsupportedOperationException.class
        );
    }

    @Test
    void pagedResponseRejectsNullAndInvalidMetadata() {
        assertThatThrownBy(() ->
                new PagedResponse<>(
                        null,
                        0,
                        1,
                        true,
                        true,
                        false,
                        false
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessageContaining("content");

        assertThatThrownBy(() ->
                new PagedResponse<>(
                        List.of(),
                        -1,
                        1,
                        true,
                        true,
                        false,
                        false
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining("page");
    }
}