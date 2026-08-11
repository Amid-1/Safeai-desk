package ru.safeai.gateway.audit.repository;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import ru.safeai.gateway.audit.entity.AuditEventEntity;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditRepositoryContractTest {

    @Test
    void findAllDoesNotForceJoinToCurrentUserTable()
            throws Exception {

        Method method =
                AuditEventRepository.class.getMethod(
                        "findAll",
                        Specification.class,
                        Pageable.class
                );

        assertThat(
                method.getAnnotation(EntityGraph.class)
        ).isNull();
    }

    @Test
    void entityCallbackDoesNotInventSystemTime()
            throws Exception {

        AuditEventEntity entity =
                new AuditEventEntity();

        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(UUID.randomUUID());
        entity.setEventType("USER_LOGIN_SUCCESS");
        entity.setDetails(Map.of());

        /*
         * createdAt намеренно не устанавливаем:
         * тест проверяет, что persistence callback
         * не подставляет системное время самостоятельно.
         */

        Method callback =
                AuditEventEntity.class
                        .getDeclaredMethod(
                                "validateBeforePersist"
                        );

        callback.setAccessible(true);

        assertThatThrownBy(() ->
                callback.invoke(entity)
        )
                .isInstanceOf(
                        InvocationTargetException.class
                )
                .hasCauseInstanceOf(
                        IllegalStateException.class
                )
                .hasRootCauseMessage(
                        "AuditEventEntity.createdAt "
                                + "должен быть установлен явно"
                );
    }

    @Test
    void auditEventEntityHasNoCurrentUserAssociation() {
        assertThat(
                Arrays.stream(
                                AuditEventEntity.class
                                        .getDeclaredFields()
                        )
                        .map(Field::getName)
        ).doesNotContain("user");
    }
}