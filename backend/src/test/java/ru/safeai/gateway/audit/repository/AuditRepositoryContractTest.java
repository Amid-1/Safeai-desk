package ru.safeai.gateway.audit.repository;

import org.hibernate.annotations.Immutable;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.test.util.ReflectionTestUtils;
import ru.safeai.gateway.audit.entity.AuditEventEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuditRepositoryContractTest {

    @Test
    void findAllDoesNotForceJoinToCurrentUserTable() throws Exception {
        Method method = AuditEventRepository.class.getMethod(
                "findAll", Specification.class, Pageable.class);
        assertThat(method.getAnnotation(EntityGraph.class)).isNull();
    }

    @Test
    void materializedAuditEvidenceIsImmutableAndDoesNotInventSystemTime() {
        assertThat(AuditEventEntity.class.getAnnotation(Immutable.class)).isNotNull();
        assertThat(AuditEventEntity.class.getDeclaredMethods())
                .extracting(Method::getName)
                .doesNotContain("validateBeforePersist", "setCreatedAt");

        AuditEventEntity entity = new AuditEventEntity();
        UUID eventId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        ReflectionTestUtils.setField(entity, "id", eventId);
        ReflectionTestUtils.setField(entity, "organizationId", organizationId);
        ReflectionTestUtils.setField(entity, "eventType", "USER_LOGIN_SUCCESS");
        ReflectionTestUtils.setField(entity, "details", Map.of());

        // The materialized entity never fabricates a timestamp on construction.
        // No lifecycle callback is defined here: the PostgreSQL/outbox write
        // boundary, not this read model, is responsible for persistence evidence.
        assertThat(entity.getCreatedAt()).isNull();
        assertThat(entity.getId()).isEqualTo(eventId);
        assertThat(entity.getOrganizationId()).isEqualTo(organizationId);
        assertThat(entity.getEventType()).isEqualTo("USER_LOGIN_SUCCESS");

        Field createdAt = Arrays.stream(AuditEventEntity.class.getDeclaredFields())
                .filter(field -> field.getName().equals("createdAt"))
                .findFirst().orElseThrow();
        assertThat(Modifier.isPrivate(createdAt.getModifiers())).isTrue();
        assertThat(createdAt.getAnnotation(jakarta.persistence.Column.class).updatable()).isFalse();
        assertThat(createdAt.getAnnotation(jakarta.persistence.Column.class).nullable()).isFalse();
    }

    @Test
    void auditEventEntityHasNoCurrentUserAssociation() {
        assertThat(Arrays.stream(AuditEventEntity.class.getDeclaredFields())
                .map(Field::getName))
                .doesNotContain("user", "userId");
        assertThat(Arrays.stream(AuditEventEntity.class.getDeclaredFields())
                .filter(field -> field.isAnnotationPresent(jakarta.persistence.ManyToOne.class))
                .toList()).isEmpty();
    }
}
