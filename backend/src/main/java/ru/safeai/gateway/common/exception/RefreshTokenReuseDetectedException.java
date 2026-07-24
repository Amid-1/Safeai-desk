package ru.safeai.gateway.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.Objects;
import java.util.UUID;

@Getter
public final class RefreshTokenReuseDetectedException
        extends ApiException {

    private final UUID userId;
    private final UUID organizationId;
    private final UUID tokenFamilyId;

    public RefreshTokenReuseDetectedException(
            String internalMessage,
            UUID userId,
            UUID organizationId,
            UUID tokenFamilyId
    ) {
        super(
                HttpStatus.UNAUTHORIZED,
                ApiErrorCode.INVALID_REFRESH_TOKEN,
                "Недействительный refresh token",
                new IllegalStateException(
                        Objects.requireNonNull(
                                internalMessage,
                                "internalMessage не должен быть null"
                        )
                )
        );

        this.userId = Objects.requireNonNull(
                userId,
                "userId не должен быть null"
        );
        this.organizationId = Objects.requireNonNull(
                organizationId,
                "organizationId не должен быть null"
        );
        this.tokenFamilyId = Objects.requireNonNull(
                tokenFamilyId,
                "tokenFamilyId не должен быть null"
        );
    }
}