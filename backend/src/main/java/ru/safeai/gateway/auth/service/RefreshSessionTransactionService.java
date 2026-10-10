package ru.safeai.gateway.auth.service;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.auth.exception.ExpiredRefreshTokenException;
import ru.safeai.gateway.auth.exception.InvalidRefreshTokenException;
import ru.safeai.gateway.auth.exception.RefreshTokenReuseDetectedException;
import ru.safeai.gateway.common.security.JwtService;

import java.util.Objects;

/**
 * Refresh rotation + local access-JWT signing transaction boundary.
 *
 * <p>JWT signing happens before transaction commit. Therefore, a local signing
 * failure rolls back the refresh rotation and does not burn a one-time refresh
 * token merely because the new access token could not be produced.</p>
 *
 * <p>Controlled refresh failures are deliberately configured with
 * {@code noRollbackFor}: some of them persist security-state transitions such
 * as family expiration or reuse termination which must survive the exception
 * returned to the client.</p>
 *
 * <p>HTTP cookie/network delivery necessarily remains outside the database
 * transaction; that ambiguity cannot be eliminated without weakening strict
 * one-time refresh semantics or introducing a different protocol.</p>
 */
@Service
@RequiredArgsConstructor
public class RefreshSessionTransactionService {

    private final RefreshTokenService
            refreshTokenService;

    private final JwtService
            jwtService;

    @Transactional(
            noRollbackFor = {
                    InvalidRefreshTokenException.class,
                    ExpiredRefreshTokenException.class,
                    RefreshTokenReuseDetectedException.class
            }
    )
    public RefreshSessionResult rotateAndSign(
            String rawRefreshToken,
            HttpServletRequest request
    ) {
        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );

        RefreshTokenService.RefreshTokenRotationResult rotation =
                refreshTokenService.rotate(
                        rawRefreshToken,
                        request
                );

        /*
         * Keep signing inside this transaction. Any unexpected RuntimeException
         * from the local JWT encoder is rollback-worthy and therefore must not
         * be added to noRollbackFor.
         */
        String accessToken =
                jwtService.generateToken(
                        rotation.accessTokenSubject()
                );

        return new RefreshSessionResult(
                accessToken,
                rotation.rawRefreshToken(),
                rotation.refreshCookieMaxAge()
        );
    }
}
