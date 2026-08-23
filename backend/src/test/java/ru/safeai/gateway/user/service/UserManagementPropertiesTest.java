package ru.safeai.gateway.user.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserManagementPropertiesTest {

    @Test
    void nullRetentionUsesSevenDayDefault() {
        UserManagementProperties properties =
                new UserManagementProperties(null);

        assertThat(properties.permanentDeletionRetention())
                .isEqualTo(Duration.ofDays(7));
    }

    @Test
    void zeroRetentionIsAllowed() {
        UserManagementProperties properties =
                new UserManagementProperties(Duration.ZERO);

        assertThat(properties.permanentDeletionRetention())
                .isZero();
    }

    @Test
    void negativeRetentionIsRejected() {
        assertThatThrownBy(() ->
                new UserManagementProperties(
                        Duration.ofSeconds(-1)
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("не может быть отрицательным");
    }

    @Test
    void retentionAbove365DaysIsRejected() {
        assertThatThrownBy(() ->
                new UserManagementProperties(
                        Duration.ofDays(366)
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("365 дней");
    }
}
