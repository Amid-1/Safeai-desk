package ru.safeai.gateway.knowledge.storage;

import java.io.IOException;

/**
 * Permanent S3 version deletion was refused and the same version was verified
 * as protected by active Object Lock retention or legal hold.
 *
 * <p>Never construct this from an error code alone. Generic access-denied or
 * invalid-request errors are not proof of legal retention.</p>
 */
public final class VerifiedObjectLockException extends IOException {
    public VerifiedObjectLockException(String message, Throwable cause) {
        super(message, cause);
    }
}
