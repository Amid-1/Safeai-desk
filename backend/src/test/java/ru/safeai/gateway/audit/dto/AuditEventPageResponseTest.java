package ru.safeai.gateway.audit.dto;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuditEventPageResponseTest {

    @Test
    void from_shouldExposeStablePageContract() {
        AuditEventResponse event = new AuditEventResponse(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Demo Company",
                "admin@test.com",
                "Demo Admin",
                "USER_LOGIN_SUCCESS",
                Map.of("ip", "127.0.0.1"),
                Instant.parse("2026-08-11T18:00:00Z")
        );

        PageImpl<AuditEventResponse> page =
                new PageImpl<>(
                        List.of(event),
                        PageRequest.of(1, 50),
                        123
                );

        AuditEventPageResponse response =
                AuditEventPageResponse.from(page);

        assertThat(response.content())
                .containsExactly(event);
        assertThat(response.number()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(50);
        assertThat(response.totalElements()).isEqualTo(123);
        assertThat(response.totalPages()).isEqualTo(3);
    }
}