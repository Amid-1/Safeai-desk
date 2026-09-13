package ru.safeai.gateway.model.service;

import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.model.domain.ModelLifecycle;
import ru.safeai.gateway.model.domain.ModelModality;
import ru.safeai.gateway.model.domain.ModelPricingStatus;
import ru.safeai.gateway.model.domain.ModelRetentionStatus;
import ru.safeai.gateway.model.domain.ModelTrainingUseStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

final class ModelCatalogRules {

    private static final Pattern MODEL_KEY = Pattern.compile(
            "^[a-z0-9][a-z0-9._:/-]{0,159}$"
    );
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ModelCatalogRules() {
    }

    static BigDecimal normalizeMoney(BigDecimal value, String field) {
        return ModelControlPlaneNumericValidation
                .normalizeNonNegativeNumeric30Scale12(value, field);
    }

    static void validateCatalogSemantics(
            ModelLifecycle lifecycle,
            int maxInputTokens,
            int maxOutputTokens,
            Set<ModelModality> inputModalities,
            Set<ModelModality> outputModalities,
            ModelRetentionStatus retentionStatus,
            Integer retentionDays,
            ModelTrainingUseStatus trainingUseStatus,
            ModelPricingStatus pricingStatus,
            boolean pricingComplete,
            BigDecimal input,
            BigDecimal cachedInput,
            BigDecimal cacheWrite,
            BigDecimal output,
            String pricingVersion,
            String extraPricingJson
    ) {
        Objects.requireNonNull(lifecycle, "lifecycle не должен быть null");
        Objects.requireNonNull(retentionStatus, "retentionStatus не должен быть null");
        Objects.requireNonNull(trainingUseStatus, "trainingUseStatus не должен быть null");
        Objects.requireNonNull(pricingStatus, "pricingStatus не должен быть null");

        if (maxInputTokens <= 0 || maxOutputTokens <= 0) {
            throw new BadRequestException(
                    "Model token limits должны быть положительными"
            );
        }
        if (inputModalities.isEmpty() || outputModalities.isEmpty()) {
            throw new BadRequestException(
                    "Model modalities не должны быть пустыми"
            );
        }
        if (outputModalities.contains(ModelModality.IMAGE)) {
            throw new BadRequestException(
                    "Model catalog не поддерживает IMAGE output modality"
            );
        }
        if (retentionDays != null && retentionDays < 0) {
            throw new BadRequestException(
                    "retentionDays не может быть отрицательным"
            );
        }
        if (retentionStatus == ModelRetentionStatus.ZERO_DATA_RETENTION
                && retentionDays != null
                && retentionDays != 0) {
            throw new BadRequestException(
                    "ZERO_DATA_RETENTION требует retentionDays=0 или null"
            );
        }

        ModelControlPlaneNumericValidation.requireNonNegativeNumeric30Scale12(
                input, "inputUsdPer1mTokens"
        );
        ModelControlPlaneNumericValidation.requireNonNegativeNumeric30Scale12(
                cachedInput, "cachedInputUsdPer1mTokens"
        );
        ModelControlPlaneNumericValidation.requireNonNegativeNumeric30Scale12(
                cacheWrite, "cacheWriteInputUsdPer1mTokens"
        );
        ModelControlPlaneNumericValidation.requireNonNegativeNumeric30Scale12(
                output, "outputUsdPer1mTokens"
        );
        ModelControlPlaneNumericValidation.requireWorstCaseCostFitsNumeric30Scale12(
                input, maxInputTokens, output, maxOutputTokens
        );

        boolean emptyExtraPricing = "{}".equals(extraPricingJson);

        switch (pricingStatus) {
            case UNPRICED -> {
                if (pricingComplete
                        || input != null
                        || cachedInput != null
                        || cacheWrite != null
                        || output != null
                        || !emptyExtraPricing) {
                    throw new BadRequestException(
                            "UNPRICED требует pricingComplete=false, "
                                    + "отсутствие всех цен и пустой extraPricingJson"
                    );
                }
            }
            case FREE -> {
                if (!pricingComplete
                        || input == null
                        || input.signum() != 0
                        || output == null
                        || output.signum() != 0
                        || (cachedInput != null && cachedInput.signum() != 0)
                        || (cacheWrite != null && cacheWrite.signum() != 0)
                        || !emptyExtraPricing) {
                    throw new BadRequestException(
                            "FREE требует complete pricing, нулевые цены "
                                    + "и пустой extraPricingJson"
                    );
                }
            }
            case CONFIGURED -> {
                if (!pricingComplete
                        || input == null
                        || output == null
                        || pricingVersion == null
                        || !emptyExtraPricing) {
                    throw new BadRequestException(
                            "CONFIGURED требует complete pricing, input/output prices, "
                                    + "pricingVersion и пустой extraPricingJson"
                    );
                }
                if (cachedInput != null && cachedInput.compareTo(input) > 0) {
                    throw new BadRequestException(
                            "cachedInputUsdPer1mTokens не может превышать inputUsdPer1mTokens"
                    );
                }
                if (cacheWrite != null && cacheWrite.compareTo(input) > 0) {
                    throw new BadRequestException(
                            "cacheWriteInputUsdPer1mTokens не может превышать inputUsdPer1mTokens"
                    );
                }
            }
            case INCOMPLETE -> {
                if (pricingComplete) {
                    throw new BadRequestException(
                            "INCOMPLETE не может иметь pricingComplete=true"
                    );
                }
            }
        }
    }

    static String validateExtraPricingJson(String value) {
        String json = value == null || value.isBlank()
                ? "{}"
                : value.trim();
        try {
            JsonNode node = JSON.readTree(json);
            if (node == null || !node.isObject()) {
                throw new BadRequestException(
                        "extraPricingJson должен быть JSON object"
                );
            }
            return JSON.writeValueAsString(node);
        } catch (BadRequestException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BadRequestException(
                    "extraPricingJson содержит некорректный JSON",
                    exception
            );
        }
    }

    static String normalizeModelKey(String value) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException("modelKey не должен быть пустым");
        }

        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!MODEL_KEY.matcher(normalized).matches()) {
            throw new BadRequestException(
                    "modelKey содержит недопустимые символы"
            );
        }
        return normalized;
    }

    static String normalizeProvider(String value) {
        String normalized = normalizeText(
                value,
                32,
                "provider"
        );

        rejectControlCharacters(
                normalized,
                "provider"
        );

        return normalized.toLowerCase(
                Locale.ROOT
        );
    }

    static String normalizeRequired(
            String value,
            int max,
            String field
    ) {
        String normalized = normalizeText(value, max, field);

        if ("providerModelId".equals(field)
                || "runtime.model".equals(field)) {
            rejectControlCharacters(
                    normalized,
                    field
            );
        }

        return normalized;
    }

    private static String normalizeText(
            String value,
            int max,
            String field
    ) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(
                    field + " не должен быть пустым"
            );
        }

        String normalized = value.trim();
        if (normalized.length() > max) {
            throw new BadRequestException(
                    field + " превышает " + max + " символов"
            );
        }
        return normalized;
    }

    private static void rejectControlCharacters(
            String value,
            String field
    ) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(
                    value.charAt(index)
            )) {
                throw new BadRequestException(
                        field + " не должен содержать control characters"
                );
            }
        }
    }

    static String normalizePricingVersion(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String normalized = value.trim();
        if (normalized.length() > 64) {
            throw new BadRequestException(
                    "pricingVersion превышает 64 символа"
            );
        }
        return normalized;
    }
}
