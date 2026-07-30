package ru.safeai.gateway.audit.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import ru.safeai.gateway.audit.config.AuditDetailsProperties;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuditDetailsSanitizerTest {

    private final JsonMapper jsonMapper =
            JsonMapper.builder().build();

    private final AuditDetailsSanitizer sanitizer =
            sanitizerWithDefaults();

    @Test
    void rawAccessTokenIsRedacted() {
        assertRedacted("accessToken");
    }

    @Test
    void rawRefreshTokenIsRedacted() {
        assertRedacted("refreshToken");
    }

    @Test
    void passwordHashIsRedacted() {
        assertRedacted("passwordHash");
    }

    @Test
    void tokenFamilyIdIsPreserved() {
        UUID tokenFamilyId = UUID.randomUUID();

        Map<String, Object> result =
                sanitizer.sanitize(
                        Map.of(
                                "tokenFamilyId",
                                tokenFamilyId
                        )
                );

        assertThat(result)
                .containsEntry(
                        "tokenFamilyId",
                        tokenFamilyId.toString()
                );
    }

    @Test
    void tokenVersionIsPreserved() {
        Map<String, Object> result =
                sanitizer.sanitize(
                        Map.of("tokenVersion", 7L)
                );

        assertThat(result)
                .containsEntry("tokenVersion", 7L);
    }

    @Test
    void inputAndOutputTokensArePreserved() {
        Map<String, Object> result =
                sanitizer.sanitize(
                        Map.of(
                                "inputTokens", 123,
                                "outputTokens", 456
                        )
                );

        assertThat(result)
                .containsEntry("inputTokens", 123)
                .containsEntry("outputTokens", 456);
    }

    @Test
    void aiResponseStatusAndResponseTimeArePreserved() {
        Map<String, Object> result =
                sanitizer.sanitize(
                        Map.of(
                                "aiResponseStatus",
                                "COMPLETED",
                                "responseTimeMs",
                                900L
                        )
                );

        assertThat(result)
                .containsEntry(
                        "aiResponseStatus",
                        "COMPLETED"
                )
                .containsEntry(
                        "responseTimeMs",
                        900L
                );
    }

    @Test
    void promptContentIsNotPersisted() {
        Map<String, Object> result =
                sanitizer.sanitize(
                        Map.of(
                                "promptContent",
                                "private prompt"
                        )
                );

        assertThat(result)
                .containsEntry(
                        "promptContent",
                        "[REDACTED]"
                )
                .doesNotContainValue(
                        "private prompt"
                );
    }

    @Test
    void nestedSensitiveValuesAreRedacted() {
        Map<String, Object> result =
                sanitizer.sanitize(
                        Map.of(
                                "request",
                                Map.of(
                                        "headers",
                                        Map.of(
                                                "authorization",
                                                "Bearer secret"
                                        ),
                                        "credential",
                                        "secret",
                                        "safe",
                                        "visible"
                                ),
                                "items",
                                List.of(
                                        Map.of(
                                                "privateKey",
                                                "key"
                                        )
                                )
                        )
                );

        @SuppressWarnings("unchecked")
        Map<String, Object> request =
                (Map<String, Object>) result.get(
                        "request"
                );

        @SuppressWarnings("unchecked")
        Map<String, Object> headers =
                (Map<String, Object>) request.get(
                        "headers"
                );

        @SuppressWarnings("unchecked")
        List<Object> items =
                (List<Object>) result.get("items");

        @SuppressWarnings("unchecked")
        Map<String, Object> firstItem =
                (Map<String, Object>) items.getFirst();

        assertThat(headers)
                .containsEntry(
                        "authorization",
                        "[REDACTED]"
                );

        assertThat(request)
                .containsEntry(
                        "credential",
                        "[REDACTED]"
                )
                .containsEntry(
                        "safe",
                        "visible"
                );

        assertThat(firstItem)
                .containsEntry(
                        "privateKey",
                        "[REDACTED]"
                );
    }

    @Test
    void exactLeafKeyInQualifiedPathIsRedacted() {
        Map<String, Object> result =
                sanitizer.sanitize(
                        Map.of(
                                "headers.authorization",
                                "Bearer secret",
                                "payload/requestBody",
                                "raw body"
                        )
                );

        assertThat(result.values())
                .containsOnly("[REDACTED]");
    }

    @Test
    void secretBearingQualifiedSubtreeIsRedacted() {
        Map<String, Object> result =
                sanitizer.sanitize(
                        Map.of(
                                "responseBody.content",
                                "provider secret",
                                "response.status",
                                "COMPLETED"
                        )
                );

        assertThat(result)
                .containsEntry(
                        "responseBody.content",
                        "[REDACTED]"
                )
                .containsEntry(
                        "response.status",
                        "COMPLETED"
                );
    }

    @Test
    void stringBudgetDoesNotSplitUnicodeSurrogatePair() {
        AuditDetailsSanitizer constrained =
                new AuditDetailsSanitizer(
                        jsonMapper,
                        new AuditDetailsProperties(
                                4,
                                10,
                                100,
                                100,
                                16,
                                1_024,
                                256
                        )
                );

        Map<String, Object> result =
                constrained.sanitize(
                        Map.of(
                                "value",
                                "A".repeat(15)
                                        + "😀B"
                        )
                );

        assertThat(result.get("value"))
                .isEqualTo("A".repeat(15));
    }

    @Test
    void nonFiniteNumbersAreConvertedToSafeMarker() {
        Map<String, Object> result =
                sanitizer.sanitize(
                        Map.of(
                                "nan", Double.NaN,
                                "positiveInfinity",
                                Double.POSITIVE_INFINITY,
                                "negativeInfinity",
                                Float.NEGATIVE_INFINITY
                        )
                );

        assertThat(result.values())
                .containsOnly(
                        "[NON_FINITE_NUMBER]"
                );
    }

    @Test
    void excessivelyLargeNumberIsConvertedToSafeMarker() {
        Map<String, Object> result =
                sanitizer.sanitize(
                        Map.of(
                                "value",
                                new BigDecimal(
                                        "9".repeat(500)
                                )
                        )
                );

        assertThat(result)
                .containsEntry(
                        "value",
                        "[NUMBER_TOO_LARGE]"
                );
    }

    @Test
    void hugeDecimalExponentIsRejectedEvenWhenTextIsShort() {
        Map<String, Object> result =
                sanitizer.sanitize(
                        Map.of(
                                "value",
                                new BigDecimal(
                                        "1E+1000000"
                                )
                        )
                );

        assertThat(result)
                .containsEntry(
                        "value",
                        "[NUMBER_TOO_LARGE]"
                );
    }

    @Test
    void unsupportedObjectIsRepresentedByTypeMarker() {
        Object unsupported = new Object();

        Map<String, Object> result =
                sanitizer.sanitize(
                        Map.of("value", unsupported)
                );

        assertThat(
                result.get("value")
        )
                .asString()
                .startsWith(
                        "[UNSUPPORTED_TYPE:"
                );
    }

    @Test
    void totalSizeBudgetIsEnforced() {
        AuditDetailsSanitizer constrained =
                new AuditDetailsSanitizer(
                        jsonMapper,
                        new AuditDetailsProperties(
                                4,
                                100,
                                500,
                                10_000,
                                1_024,
                                1_024,
                                256
                        )
                );

        Map<String, Object> large =
                new LinkedHashMap<>();

        for (int index = 0; index < 100; index++) {
            large.put(
                    "field-" + index,
                    "я".repeat(1_000)
            );
        }

        Map<String, Object> result =
                constrained.sanitize(large);

        int bytes = jsonMapper
                .writeValueAsBytes(result)
                .length;

        assertThat(bytes).isLessThanOrEqualTo(1_024);

        assertThat(result.toString())
                .containsAnyOf(
                        "_truncated",
                        "_budgetExceeded",
                        "[BUDGET_EXCEEDED]",
                        "MAX_JSON_BYTES"
                );
    }

   @Test
void globalNodeBudgetStopsExponentialStructure() {
    AuditDetailsSanitizer constrained =
            new AuditDetailsSanitizer(
                    jsonMapper,
                    new AuditDetailsProperties(
                            8,
                            100,
                            10,
                            10_000,
                            1_024,
                            61_440,
                            256
                    )
            );

    Map<String, Object> nested =
            Map.of(
                    "a",
                    List.of(
                            Map.of(
                                    "b",
                                    List.of(
                                            1, 2, 3, 4, 5,
                                            6, 7, 8, 9, 10
                                    )
                            )
                    )
            );

    Map<String, Object> result =
            constrained.sanitize(nested);

    assertThat(result.toString())
            .containsAnyOf(
                    "[BUDGET_EXCEEDED]",
                    "_budgetExceeded"
            );
}

    @Test
    void jsonSerializationFailureIsFailClosed() {
        JsonMapper failingMapper =
                mock(JsonMapper.class);

        when(failingMapper.writeValueAsBytes(any()))
                .thenThrow(
                        new JacksonException(
                                "serialization failed"
                        ) {
                        }
                );

        AuditDetailsSanitizer failingSanitizer =
                new AuditDetailsSanitizer(
                        failingMapper,
                        defaultProperties()
                );

        assertThatThrownBy(() ->
                failingSanitizer.sanitize(
                        Map.of("safe", "value")
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "не сериализуется в JSON"
                );
    }

    private void assertRedacted(String key) {
        Map<String, Object> result =
                sanitizer.sanitize(
                        Map.of(key, "raw-secret")
                );

        assertThat(result)
                .containsEntry(
                        key,
                        "[REDACTED]"
                )
                .doesNotContainValue(
                        "raw-secret"
                );
    }

    private AuditDetailsSanitizer sanitizerWithDefaults() {
        return new AuditDetailsSanitizer(
                jsonMapper,
                defaultProperties()
        );
    }

    private AuditDetailsProperties defaultProperties() {
        return new AuditDetailsProperties(
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
