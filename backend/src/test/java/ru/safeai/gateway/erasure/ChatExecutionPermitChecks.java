// SafeAI-Desk/backend/src/test/java/ru/safeai/gateway/erasure/ChatExecutionPermitChecks.java
package ru.safeai.gateway.erasure;

import java.lang.reflect.RecordComponent;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static ru.safeai.gateway.erasure.ChatExecutionPermitVerifier.*;

/** Shared JDK-only checks; also executed by the JUnit adapter. No test key is persisted. */
final class ChatExecutionPermitChecks {
    private static final Instant NOW = Instant.parse("2026-11-27T20:00:00Z");
    private static final Instant FROM = NOW.minusSeconds(1);
    private static final Instant TO = NOW.plusSeconds(120);
    private final EnumMap<Purpose, KeyPair> keys = new EnumMap<>(Purpose.class);
    private final List<TrustedKey> anchors = new ArrayList<>();
    private final Binding binding = new Binding(id(1), id(2), id(3), id(4), id(5), id(6),
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, "a".repeat(64), "b".repeat(64), id(7), id(8), id(9));
    private final Envelope envelope = new Envelope(id(10), binding, FROM, TO);
    private final List<Proof> proofs = new ArrayList<>();
    private final ChatExecutionPermitVerifier verifier;
    private int passed;

    private ChatExecutionPermitChecks() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        for (Purpose p : Purpose.values()) {
            KeyPair pair = generator.generateKeyPair();
            keys.put(p, pair);
            anchors.add(new TrustedKey(p, p.name(), pair.getPublic(), NOW.minusSeconds(600), NOW.plusSeconds(600), false));
            proofs.add(sign(p, p.name(), id(20 + p.ordinal()), FROM, TO, envelope, pair));
        }
        verifier = verifier(anchors, NOW);
    }
    private static UUID id(long value) { return new UUID(0, value); }
    private static ChatExecutionPermitVerifier verifier(List<TrustedKey> anchors, Instant now) {
        return new ChatExecutionPermitVerifier(anchors, Clock.fixed(now, ZoneOffset.UTC));
    }
    private static Proof sign(Purpose purpose, String keyId, UUID nonce, Instant from, Instant to,
                              Envelope env, KeyPair key) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(key.getPrivate());
        signature.update(signingBytes(env, purpose, keyId, nonce, from, to));
        return new Proof(purpose, keyId, nonce, from, to, HexFormat.of().formatHex(signature.sign()));
    }
    private void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        passed++;
    }
    private void denied(Envelope e, List<Proof> p, Binding b, String label) {
        check(verifier.verify(e, p, b) == Result.DENIED, label);
    }
    private static <T> List<T> replace(List<T> list, int i, T value) {
        var copy = new ArrayList<>(list); copy.set(i, value); return copy;
    }
    private void invalid(Runnable action, String label) {
        try { action.run(); } catch (IllegalArgumentException | NullPointerException expected) { passed++; return; }
        throw new AssertionError(label);
    }
    private void signaturesAndBinding() throws Exception {
        check(verifier.verify(envelope, proofs, binding) == Result.VERIFIED_ENVELOPE_ONLY, "positive control");
        check(verifier.verify(envelope, proofs.reversed(), binding) == Result.VERIFIED_ENVELOPE_ONLY, "proof order independent");
        denied(null, proofs, binding, "missing envelope");
        denied(envelope, proofs, null, "missing observation");
        denied(envelope, null, binding, "missing proofs");
        denied(envelope, proofs.subList(0, 3), binding, "missing proof");
        denied(envelope, replace(proofs, 0, null), binding, "null proof");
        denied(envelope, replace(proofs, 0, proofs.get(1)), binding, "duplicate purpose");
        denied(new Envelope(id(99), binding, FROM, TO), proofs, binding, "permit substitution");
        denied(new Envelope(envelope.permitId(), binding, FROM, TO.minusSeconds(1)), proofs, binding, "permit window substitution");
        // Alter every signed binding field individually. Even when the trusted
        // observation equals the changed binding, the original signatures fail.
        RecordComponent[] fields = Binding.class.getRecordComponents();
        var constructor = Binding.class.getDeclaredConstructor(
                UUID.class, UUID.class, UUID.class, UUID.class, UUID.class, UUID.class,
                long.class, long.class, long.class, long.class, long.class,
                long.class, long.class, long.class, long.class, long.class,
                String.class, String.class,
                UUID.class, UUID.class, UUID.class
        );
        Object[] original = new Object[fields.length];
        for (int i = 0; i < fields.length; i++) original[i] = fields[i].getAccessor().invoke(binding);
        for (int i = 0; i < fields.length; i++) {
            Object[] changed = original.clone();
            changed[i] = switch (original[i]) {
                case UUID ignored -> id(1000 + i);
                case Long n -> n + 1;
                case String ignored -> "c".repeat(64);
                default -> throw new AssertionError("Unknown signed field");
            };
            Binding changedBinding = constructor.newInstance(changed);
            denied(envelope, proofs, changedBinding, "stale " + fields[i].getName());
            denied(new Envelope(envelope.permitId(), changedBinding, FROM, TO), proofs, changedBinding,
                    "tampered " + fields[i].getName());
        }
        for (int i = 0; i < proofs.size(); i++) {
            Proof p = proofs.get(i);
            String flipped = (p.signature().startsWith("0") ? "1" : "0") + p.signature().substring(1);
            denied(envelope, replace(proofs, i, new Proof(p.purpose(), p.keyId(), p.nonce(), FROM, TO, flipped)), binding, "signature tampering " + p.purpose());
            denied(envelope, replace(proofs, i, new Proof(p.purpose(), "unknown", p.nonce(), FROM, TO, p.signature())), binding, "unknown key " + p.purpose());
            denied(envelope, replace(proofs, i, new Proof(p.purpose(), p.keyId(), id(1500+i), FROM, TO, p.signature())), binding, "nonce tampering " + p.purpose());
            denied(envelope, replace(proofs, i, sign(p.purpose(), p.keyId(), p.nonce(), FROM, TO, envelope,
                    keys.get(Purpose.values()[(i + 1) % 4]))), binding, "wrong signer " + p.purpose());
        }
        Proof p = proofs.getFirst();
        denied(envelope, replace(proofs, 0, sign(p.purpose(), p.keyId(), proofs.get(1).nonce(), FROM, TO, envelope, keys.get(p.purpose()))), binding, "duplicate nonce even when validly signed");
        denied(envelope, replace(proofs, 0, sign(p.purpose(), p.keyId(), envelope.permitId(), FROM, TO, envelope, keys.get(p.purpose()))), binding, "permit ID reused as nonce");
        denied(envelope, replace(proofs, 0, sign(p.purpose(), p.keyId(), p.nonce(), FROM, TO.minusSeconds(1), envelope, keys.get(p.purpose()))), binding, "permit outlives proof");
        denied(envelope, replace(proofs, 0, sign(p.purpose(), p.keyId(), p.nonce(), NOW, TO, envelope, keys.get(p.purpose()))), binding, "proof issued after permit");
        denied(envelope, replace(proofs, 0, sign(p.purpose(), p.keyId(), p.nonce(), NOW.plusSeconds(1), TO, envelope, keys.get(p.purpose()))), binding, "future proof");
    }
    private void trustAndTime() throws Exception {
        check(verifier(anchors, FROM).verify(envelope, proofs, binding) == Result.VERIFIED_ENVELOPE_ONLY, "issue boundary inclusive");
        check(verifier(anchors, TO).verify(envelope, proofs, binding) == Result.DENIED, "expiry boundary exclusive");
        check(verifier(anchors, FROM.minusNanos(1)).verify(envelope, proofs, binding) == Result.DENIED, "future permit");
        TrustedKey k = anchors.getFirst();
        check(verifier(replace(anchors, 0, new TrustedKey(k.purpose(), k.keyId(), k.key(), k.validFrom(), k.validUntil(), true)), NOW)
                .verify(envelope, proofs, binding) == Result.DENIED, "revoked signer");
        check(verifier(replace(anchors, 0, new TrustedKey(k.purpose(), k.keyId(), k.key(), NOW, k.validUntil(), false)), NOW)
                .verify(envelope, proofs, binding) == Result.DENIED, "key not valid at issue");
        check(verifier(replace(anchors, 0, new TrustedKey(k.purpose(), k.keyId(), k.key(), k.validFrom(), TO.minusSeconds(1), false)), NOW)
                .verify(envelope, proofs, binding) == Result.DENIED, "proof outlives key");
        invalid(() -> verifier(anchors.subList(0, 3), NOW), "missing anchor");
        invalid(() -> verifier(replace(anchors, 1, new TrustedKey(Purpose.MANIFEST, "different-id", k.key(), k.validFrom(), k.validUntil(), false)), NOW), "same public key across roles");
        invalid(() -> verifier(replace(anchors, 1, new TrustedKey(Purpose.MANIFEST, k.keyId(), anchors.get(1).key(), k.validFrom(), k.validUntil(), false)), NOW), "duplicate key id");
        var rsa = KeyPairGenerator.getInstance("RSA"); rsa.initialize(2048);
        var wrongKey = rsa.generateKeyPair().getPublic();
        invalid(() -> verifier(replace(anchors, 0, new TrustedKey(k.purpose(), k.keyId(), wrongKey, k.validFrom(), k.validUntil(), false)), NOW), "non Ed25519 anchor");
        invalid(() -> new Envelope(id(10), binding, FROM, FROM.plusSeconds(301)), "excessive TTL");
        invalid(() -> new Envelope(id(10), binding, FROM, FROM), "zero TTL");
        invalid(() -> new Envelope(id(0), binding, FROM, TO), "nil permit ID");
        invalid(() -> new Proof(Purpose.IDENTITY, "bad key", id(20), FROM, TO, "a".repeat(128)), "key identifier validation");
        invalid(() -> new Proof(Purpose.IDENTITY, "key", id(20), FROM, TO, "a".repeat(127)), "signature length");
        invalid(() -> new Binding(id(1), id(2), id(3), id(4), id(3), id(6), 1,2,3,4,5,6,7,8,9,10, "a".repeat(64),"b".repeat(64),id(7),id(8),id(9)), "self-review");
        invalid(() -> new Binding(id(1), id(2), id(3), id(4), id(5), id(5), 1,2,3,4,5,6,7,8,9,10, "a".repeat(64),"b".repeat(64),id(7),id(8),id(9)), "same reviewer and execution approver");
        invalid(() -> new Binding(id(1), id(2), id(3), id(4), id(5), id(6), 1,2,3,4,5,6,7,8,-1,10, "a".repeat(64),"b".repeat(64),id(7),id(8),id(9)), "negative epoch");
        invalid(() -> new Binding(id(1), id(2), id(3), id(4), id(5), id(6), 1,2,3,4,5,6,7,8,9,10, "a".repeat(64),"invalid",id(7),id(8),id(9)), "invalid manifest digest");
    }
    private void evidence() throws Exception {
        var complete = new ChatErasureEvidenceAssessment.Observation(id(1),id(2),id(3),id(4),"ERASED","ERASED",NOW,FROM,true,true,true,0L,0L,true);
        var result = ChatErasureEvidenceAssessment.assess(complete);
        check(result.state() == ChatErasureEvidenceAssessment.State.V86_REDACTION_OBSERVED, "complete V86 observation");
        check(!result.completionProven() && !result.executionAvailable(), "no full completion or execution from observation");
        check(ChatErasureEvidenceAssessment.assess(null).state() == ChatErasureEvidenceAssessment.State.UNKNOWN, "no evidence");
        var fields = ChatErasureEvidenceAssessment.Observation.class.getRecordComponents();
        var constructor = ChatErasureEvidenceAssessment.Observation.class.getDeclaredConstructor(
                UUID.class, UUID.class, UUID.class, UUID.class,
                String.class, String.class,
                Instant.class, Instant.class,
                Boolean.class, Boolean.class, Boolean.class,
                Long.class, Long.class, Boolean.class
        );
        Object[] original = new Object[fields.length];
        for (int i = 0; i < fields.length; i++) original[i] = fields[i].getAccessor().invoke(complete);
        for (int i = 0; i < fields.length; i++) {
            Object[] changed = original.clone(); changed[i] = null;
            result = ChatErasureEvidenceAssessment.assess(constructor.newInstance(changed));
            check(result.state() != ChatErasureEvidenceAssessment.State.V86_REDACTION_OBSERVED && !result.completionProven(), "missing evidence " + fields[i].getName());
            if (original[i] instanceof Boolean || original[i] instanceof Long || original[i] instanceof String) {
                changed[i] = switch (original[i]) { case Boolean ignored -> false; case Long ignored -> 1L; case String ignored -> "UNKNOWN"; default -> throw new AssertionError(); };
                result = ChatErasureEvidenceAssessment.assess(constructor.newInstance(changed));
                check(result.state() != ChatErasureEvidenceAssessment.State.V86_REDACTION_OBSERVED && !result.completionProven(), "incorrect evidence " + fields[i].getName());
            }
        }
        var pending = new ChatErasureEvidenceAssessment.Observation(id(1),id(2),id(3),id(4),"ERASING","ERASING",null,null,true,false,false,1L,0L,false);
        check(ChatErasureEvidenceAssessment.assess(pending).state() == ChatErasureEvidenceAssessment.State.NOT_TERMINAL, "pending isn't completion");
    }
    static int runAll() throws Exception {
        var suite = new ChatExecutionPermitChecks();
        suite.signaturesAndBinding(); suite.trustAndTime(); suite.evidence();
        return suite.passed;
    }
    public static void main(String[] args) throws Exception {
        System.out.println("PASS: " + runAll() + " JDK checks (cryptography/evidence; no database or execution)");
    }
}
