package ru.safeai.gateway.model.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseTimestampNormalizerTest {

    @Test
    void truncatesNanosecondsToPostgresMicrosecondPrecisionWithoutRounding() {
        Instant input = Instant.parse("2026-10-03T12:34:56.123456789Z");
        assertThat(DatabaseTimestampNormalizer.normalize(input))
                .isEqualTo(Instant.parse("2026-10-03T12:34:56.123456Z"));
    }

    @Test
    void alreadyCanonicalTimestampRemainsUnchanged() {
        Instant input = Instant.parse("2026-10-03T12:34:56.123456Z");
        assertThat(DatabaseTimestampNormalizer.normalize(input)).isEqualTo(input);
    }

    @Test
    void nullTimestampFailsFast() {
        assertThatThrownBy(() -> DatabaseTimestampNormalizer.normalize(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("timestamp");
    }
}
