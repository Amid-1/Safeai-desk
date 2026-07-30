package ru.safeai.gateway.audit.service;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.common.exception.BadRequestException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditCursorCodecTest {

    private final AuditCursorCodec codec =
            new AuditCursorCodec();

    @Test
    void cursorRoundTrip() {
        Instant createdAt =
                Instant.parse(
                        "2026-07-30T08:00:00.123456Z"
                );

        UUID id = UUID.randomUUID();

        AuditCursorCodec.AuditCursor decoded =
                codec.decode(
                        codec.encode(createdAt, id)
                );

        assertThat(decoded.createdAt())
                .isEqualTo(createdAt);

        assertThat(decoded.id())
                .isEqualTo(id);
    }

    @Test
    void nullAndBlankCursorMeanFirstPage() {
        assertThat(codec.decode(null)).isNull();
        assertThat(codec.decode("   ")).isNull();
    }

    @Test
    void invalidBase64CursorIsRejected() {
        assertInvalid("not-a-valid-cursor***");
    }

    @Test
    void unsupportedCursorVersionIsRejected() {
        String unsupported = java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        (
                                "v2|"
                                        + Instant.EPOCH
                                        + "|"
                                        + UUID.randomUUID()
                        ).getBytes(
                                java.nio.charset
                                        .StandardCharsets.UTF_8
                        )
                );

        assertInvalid(unsupported);
    }

    @Test
    void malformedTimestampIsRejected() {
        String malformed = java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        (
                                "v1|not-an-instant|"
                                        + UUID.randomUUID()
                        ).getBytes(
                                java.nio.charset
                                        .StandardCharsets.UTF_8
                        )
                );

        assertInvalid(malformed);
    }

    private void assertInvalid(String cursor) {
        assertThatThrownBy(() ->
                codec.decode(cursor)
        )
                .isInstanceOf(
                        BadRequestException.class
                )
                .hasMessageContaining(
                        "Некорректный audit cursor"
                );
    }
}
