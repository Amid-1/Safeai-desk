package ru.safeai.gateway.usage.repository;

import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;
import ru.safeai.gateway.chat.entity.ChatMessageEntity;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class UsageEntityMappingContractTest {

    @Test
    void costColumnPreservesPricingScaleTwelve() throws Exception {
        Column column = column("costUsd");

        assertThat(column.name()).isEqualTo("cost_usd");
        assertThat(column.precision()).isEqualTo(30);
        assertThat(column.scale()).isEqualTo(12);
    }

    @Test
    void modelAndCurrencyLengthsMatchDatabaseContract() throws Exception {
        assertThat(column("model").length()).isEqualTo(100);
        assertThat(column("currency").length()).isEqualTo(3);
    }

    private Column column(String fieldName) throws Exception {
        Field field = ChatMessageEntity.class.getDeclaredField(fieldName);
        Column column = field.getAnnotation(Column.class);

        assertThat(column)
                .as("@Column is required on ChatMessageEntity.%s", fieldName)
                .isNotNull();

        return column;
    }
}
