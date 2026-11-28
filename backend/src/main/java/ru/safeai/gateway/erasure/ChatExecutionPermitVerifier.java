// SafeAI-Desk/backend/src/main/java/ru/safeai/gateway/erasure/ChatExecutionPermitVerifier.java
package ru.safeai.gateway.erasure;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Candidate cryptographic verification only. Not a Spring bean, issuer, durable
 * nonce consumer, database gate or authorization to erase. A verified envelope
 * must NOT be used by V86 until the independent issuers, epochs, durable replay
 * protection and transaction-bound barrier have passed their own acceptance.
 */
public final class ChatExecutionPermitVerifier {
    private static final Duration MAX_TTL = Duration.ofMinutes(5);
    private static final UUID NIL = new UUID(0, 0);

    public enum Purpose {IDENTITY, MANIFEST, INDEPENDENT_DECISION, PERMIT}

    public enum Result {VERIFIED_ENVELOPE_ONLY, DENIED}

    /**
     * All epochs must eventually be backed by trusted, monotonic DB counters.
     * Intake digest is deliberately separate from the frozen manifest digest.
     */
    public record Binding(UUID requestId, UUID organizationId, UUID ownerId, UUID chatId,
                          UUID reviewerId, UUID executionApproverId,
                          long caseVersion, long resourceVersion, long reviewVersion,
                          long ownerTokenVersion, long reviewerTokenVersion,
                          long approverTokenVersion, long organizationAuthVersion,
                          long policyVersion, long authorityEpoch, long allHoldsEpoch,
                          String intakeDigest, String manifestDigest,
                          UUID identityProofRef, UUID manifestRef, UUID decisionRef) {
        public Binding {
            for (UUID id : List.of(requestId, organizationId, ownerId, chatId, reviewerId,
                executionApproverId, identityProofRef, manifestRef, decisionRef))
                requireId(id);
            if (ownerId.equals(reviewerId) || ownerId.equals(executionApproverId)
                || reviewerId.equals(executionApproverId)) {
                throw new IllegalArgumentException("Independent actors required");
            }
            for (long version : new long[]{caseVersion, resourceVersion, reviewVersion,
                ownerTokenVersion, reviewerTokenVersion, approverTokenVersion,
                organizationAuthVersion, policyVersion, authorityEpoch, allHoldsEpoch}) {
                if (version < 0) throw new IllegalArgumentException("Invalid version");
            }
            requireDigest(intakeDigest);
            requireDigest(manifestDigest);
        }
    }

    public record Envelope(UUID permitId, Binding binding, Instant issuedAt, Instant expiresAt) {
        public Envelope {
            requireId(permitId);
            Objects.requireNonNull(binding);
            requireWindow(issuedAt, expiresAt);
        }
    }

    /**
     * Signatures are fixed-length lower-case hex to avoid mutable byte-array fields.
     */
    public record Proof(Purpose purpose, String keyId, UUID nonce, Instant issuedAt,
                        Instant expiresAt, String signature) {
        public Proof {
            Objects.requireNonNull(purpose);
            requireKeyId(keyId);
            requireId(nonce);
            requireWindow(issuedAt, expiresAt);
            if (signature == null || !signature.matches("[0-9a-f]{128}")) {
                throw new IllegalArgumentException("Invalid signature representation");
            }
        }
    }

    /**
     * Trust anchors come only from a separately reviewed external trust store.
     */
    public record TrustedKey(Purpose purpose, String keyId, PublicKey key,
                             Instant validFrom, Instant validUntil, boolean revoked) {
        public TrustedKey {
            Objects.requireNonNull(purpose);
            requireKeyId(keyId);
            Objects.requireNonNull(key);
            Objects.requireNonNull(validFrom);
            Objects.requireNonNull(validUntil);
            if (!validFrom.isBefore(validUntil) || key.getEncoded() == null) {
                throw new IllegalArgumentException("Invalid trust anchor");
            }
        }
    }

    private final Map<Purpose, TrustedKey> keys;
    private final Clock clock;

    public ChatExecutionPermitVerifier(List<TrustedKey> trustedKeys, Clock clock) {
        this.clock = Objects.requireNonNull(clock);
        var byPurpose = new EnumMap<Purpose, TrustedKey>(Purpose.class);
        var fingerprints = new HashSet<String>();
        var keyIds = new HashSet<String>();
        for (TrustedKey key : List.copyOf(trustedKeys)) {
            if (byPurpose.putIfAbsent(key.purpose(), key) != null
                || !keyIds.add(key.keyId()) || !fingerprints.add(fingerprint(key.key()))) {
                throw new IllegalArgumentException("Trust anchors must be distinct");
            }
            // Validate the algorithm at configuration time, not on first use.
            try {
                Signature.getInstance("Ed25519").initVerify(key.key());
            } catch (GeneralSecurityException failure) {
                throw new IllegalArgumentException("Invalid Ed25519 trust anchor", failure);
            }
        }
        if (byPurpose.size() != Purpose.values().length) {
            throw new IllegalArgumentException("All independent trust anchors are required");
        }
        keys = Map.copyOf(byPurpose);
    }

    /**
     * expected is an observation from a trusted reader, NOT client input.
     * Equality alone cannot exclude ABA without persisted epochs. Recheck in
     * the eventual consuming transaction. No successful result grants authority.
     */
    public Result verify(Envelope envelope, List<Proof> proofs, Binding expected) {
        if (envelope == null || proofs == null
            || !envelope.binding().equals(expected)
            || proofs.size() != Purpose.values().length) {
            return Result.DENIED;
        }
        Instant now = clock.instant();
        if (isOutsideValidityWindow(
            envelope.issuedAt(),
            envelope.expiresAt(),
            now
        )) {
            return Result.DENIED;
        }
        var seen = new HashSet<Purpose>();
        var nonces = new HashSet<UUID>();
        for (Proof proof : proofs) {
            if (proof == null || !seen.add(proof.purpose()) || !nonces.add(proof.nonce())
                || proof.nonce().equals(envelope.permitId())) return Result.DENIED;
            TrustedKey key = keys.get(proof.purpose());
            if (key == null || key.revoked() || !key.keyId().equals(proof.keyId())
                || isOutsideValidityWindow(proof.issuedAt(), proof.expiresAt(), now)
                || proof.issuedAt().isBefore(key.validFrom()) || proof.expiresAt().isAfter(key.validUntil())
                || envelope.issuedAt().isBefore(proof.issuedAt())
                || envelope.expiresAt().isAfter(proof.expiresAt())) return Result.DENIED;
            try {
                Signature verifier = Signature.getInstance("Ed25519");
                verifier.initVerify(key.key());
                verifier.update(signingBytes(envelope, proof.purpose(), proof.keyId(), proof.nonce(),
                    proof.issuedAt(), proof.expiresAt()));
                if (!verifier.verify(HexFormat.of().parseHex(proof.signature()))) return Result.DENIED;
            } catch (GeneralSecurityException failure) {
                return Result.DENIED;
            }
        }
        return seen.size() == Purpose.values().length ? Result.VERIFIED_ENVELOPE_ONLY : Result.DENIED;
    }

    /**
     * Versioned, domain-separated binary format; no JSON canonicalization ambiguity.
     * DataOutputStream uses big-endian integers and length-prefixed modified UTF-8;
     * every string here is constrained ASCII. Never sign serialized arbitrary objects.
     */
    static byte[] signingBytes(Envelope envelope, Purpose purpose, String keyId, UUID nonce,
                               Instant issuedAt, Instant expiresAt) {
        Objects.requireNonNull(envelope);
        Objects.requireNonNull(purpose);
        requireKeyId(keyId);
        requireId(nonce);
        requireWindow(issuedAt, expiresAt);
        try (var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes)) {
            out.writeUTF("SAFEAI_CHAT_EXECUTION_PERMIT_CANDIDATE_V1");
            out.writeUTF("CHAT");
            out.writeUTF("REDACT_CONTENT");
            out.writeUTF("FULL_CHAT_CONTENT");
            out.writeUTF(purpose.name());
            out.writeUTF(keyId);
            uuid(out, nonce);
            instant(out, issuedAt);
            instant(out, expiresAt);
            uuid(out, envelope.permitId());
            instant(out, envelope.issuedAt());
            instant(out, envelope.expiresAt());
            Binding b = envelope.binding();
            for (UUID id : List.of(b.requestId(), b.organizationId(), b.ownerId(), b.chatId(),
                b.reviewerId(), b.executionApproverId(), b.identityProofRef(), b.manifestRef(), b.decisionRef()))
                uuid(out, id);
            for (long version : new long[]{b.caseVersion(), b.resourceVersion(), b.reviewVersion(),
                b.ownerTokenVersion(), b.reviewerTokenVersion(), b.approverTokenVersion(),
                b.organizationAuthVersion(), b.policyVersion(), b.authorityEpoch(), b.allHoldsEpoch()})
                out.writeLong(version);
            out.writeUTF(b.intakeDigest());
            out.writeUTF(b.manifestDigest());
            out.flush();
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException("Permit canonicalization failed", failure);
        }
    }

    private static boolean isOutsideValidityWindow(
        Instant from,
        Instant to,
        Instant now
    ) {
        return now.isBefore(from) || !now.isBefore(to);
    }

    private static void requireWindow(Instant from, Instant to) {
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
        if (!from.isBefore(to) || Duration.between(from, to).compareTo(MAX_TTL) > 0) {
            throw new IllegalArgumentException("Invalid proof lifetime");
        }
    }

    private static void requireId(UUID id) {
        if (id == null || NIL.equals(id)) throw new IllegalArgumentException("Invalid identity");
    }

    private static void requireDigest(String digest) {
        if (digest == null || !digest.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid digest");
    }

    private static void requireKeyId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9._-]{1,64}")) throw new IllegalArgumentException("Invalid key id");
    }

    private static String fingerprint(PublicKey key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getEncoded()));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    private static void uuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    private static void instant(DataOutputStream out, Instant value) throws IOException {
        out.writeLong(value.getEpochSecond());
        out.writeInt(value.getNano());
    }
}
