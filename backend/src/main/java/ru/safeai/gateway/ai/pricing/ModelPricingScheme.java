package ru.safeai.gateway.ai.pricing;

import java.util.Objects;

/**
 * Immutable settlement-algorithm identity persisted by the V66 pricing schema.
 *
 * <p>The enum values are part of the database/provenance contract and therefore
 * must not be renamed or repurposed. Existing CONFIGURED legacy rows use
 * {@link #LEGACY_FLAT_UNVERIFIED_V1} and intentionally fail closed for
 * automatic settlement until a new audited catalog version explicitly selects
 * {@link #FLAT_TOKEN_V1}.</p>
 *
 * <p>This type is intentionally retained even while the corresponding pricing
 * call-site integration is being rolled out. Do not replace it with a boolean:
 * the scheme name is immutable settlement evidence.</p>
 */
@SuppressWarnings("unused")
public enum ModelPricingScheme {

    /**
     * Current automatically-settleable flat input/output token algorithm.
     */
    FLAT_TOKEN_V1(true),

    /**
     * Legacy flat pricing whose immutable settlement semantics were not proven.
     */
    LEGACY_FLAT_UNVERIFIED_V1(false),

    /**
     * A pricing shape the current automatic settlement engine cannot prove.
     */
    UNSUPPORTED_COMPLEX_V1(false);

    private final boolean supportedForAutomaticSettlement;

    ModelPricingScheme(
            boolean supportedForAutomaticSettlement
    ) {
        this.supportedForAutomaticSettlement =
                supportedForAutomaticSettlement;
    }

    public boolean supportedForAutomaticSettlement() {
        return supportedForAutomaticSettlement;
    }

    /**
     * Stable database/provenance representation.
     */
    public String databaseValue() {
        return name();
    }

    /**
     * Fail-closed parser for persisted pricing evidence.
     *
     * <p>No trimming/case folding is performed intentionally: accepting a
     * non-canonical database value would hide corrupted or manually modified
     * settlement provenance.</p>
     */
    public static ModelPricingScheme fromDatabaseValue(
            String value
    ) {
        Objects.requireNonNull(
                value,
                "pricingScheme не должен быть null"
        );

        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    "pricingScheme не должен быть пустым"
            );
        }

        try {
            return valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Неизвестный pricingScheme: " + value,
                    exception
            );
        }
    }
}
