package ru.safeai.gateway.audit.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.safeai.gateway.audit.dto.AuditIntegrityStatus;
import ru.safeai.gateway.audit.dto.AuditIntegrityStatus.ArchiveStatus;
import ru.safeai.gateway.audit.dto.AuditIntegrityStatus.State;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class AuditIntegrityReportTest {

    private static final String DATASET =
            "ac000000-0000-4000-8000-000000000001";

    private static final Instant NOW =
            Instant.parse("2026-11-21T08:00:00Z");

    private static final String VERIFIED_REPORT =
            "VERIFIED\n165\n0\n0\nAVAILABLE";

    private static final String UNAVAILABLE_REPORT =
            "UNAVAILABLE\n-1\n-1\n-1\nUNKNOWN";

    @TempDir
    Path directory;

    private Path report;
    private KeyPair key;
    private String publicKey;

    @BeforeEach
    void setup() throws Exception {
        key = KeyPairGenerator.getInstance("Ed25519")
                .generateKeyPair();

        publicKey = Base64.getEncoder()
                .encodeToString(key.getPublic().getEncoded());

        report = directory.resolve("status.txt");
    }

    @Test
    void verifiedPendingAndViolationRequireConsistentSignedFields()
            throws Exception {

        write(NOW, VERIFIED_REPORT, DATASET);

        var status = read(NOW);

        assertThat(status.state()).isEqualTo(State.VERIFIED);
        assertThat(status.checkedEvents()).isEqualTo(165L);
        assertThat(status.violations()).isZero();
        assertThat(status.unanchoredEvents()).isZero();
        assertThat(status.archiveStatus()).isEqualTo(ArchiveStatus.AVAILABLE);
        assertThat(status.checkedAt()).isEqualTo(NOW);
        assertThat(status.freshUntil()).isEqualTo(NOW.plusSeconds(900));

        write(NOW, "PENDING\n165\n0\n2\nAVAILABLE", DATASET);

        var pending = read(NOW);

        assertThat(pending.state()).isEqualTo(State.PENDING);
        assertThat(pending.unanchoredEvents()).isEqualTo(2L);

        write(NOW, "VIOLATION\n165\n1\n0\nAVAILABLE", DATASET);

        var violated = read(NOW);

        assertThat(violated.state()).isEqualTo(State.VIOLATION);
        assertThat(violated.violations()).isEqualTo(1L);

        write(NOW, "VERIFIED\n165\n1\n0\nAVAILABLE", DATASET);

        assertThat(read(NOW).state()).isEqualTo(State.INVALID);
    }

    @Test
    void tamperingWrongDatasetWrongKeyFutureAndExpiryNeverVerify()
            throws Exception {

        write(NOW, VERIFIED_REPORT, DATASET);

        assertThat(read(NOW.plusSeconds(899)).state())
                .isEqualTo(State.VERIFIED);

        assertThat(read(NOW.plusSeconds(900)).state())
                .isEqualTo(State.STALE);

        assertThat(read(NOW.minusSeconds(1)).state())
                .isEqualTo(State.INVALID);

        String foreignKey = Base64.getEncoder().encodeToString(
                KeyPairGenerator.getInstance("Ed25519")
                        .generateKeyPair()
                        .getPublic()
                        .getEncoded()
        );

        assertThat(AuditIntegrityReport.read(
                report.toString(),
                DATASET,
                foreignKey,
                NOW
        ).state()).isEqualTo(State.INVALID);

        Files.writeString(
                report,
                Files.readString(report).replace("165", "166")
        );

        assertThat(read(NOW).state()).isEqualTo(State.INVALID);

        write(
                NOW,
                VERIFIED_REPORT,
                "bc000000-0000-4000-8000-000000000001"
        );

        assertThat(read(NOW).state()).isEqualTo(State.INVALID);
    }

    @Test
    void freshnessDependsOnSignedTimestamp() throws Exception {
        Instant recent = NOW.minusSeconds(899);

        write(recent, VERIFIED_REPORT, DATASET);

        var verified = read(NOW);

        assertThat(verified.state()).isEqualTo(State.VERIFIED);
        assertThat(verified.checkedAt()).isEqualTo(recent);
        assertThat(verified.freshUntil()).isEqualTo(NOW.plusSeconds(1));

        Instant expired = NOW.minusSeconds(900);

        write(expired, VERIFIED_REPORT, DATASET);

        var stale = read(NOW);

        assertThat(stale.state()).isEqualTo(State.STALE);
        assertThat(stale.checkedAt()).isEqualTo(expired);
        assertThat(stale.checkedEvents()).isNull();
        assertThat(stale.freshUntil()).isNull();
        assertThat(stale.archiveStatus()).isEqualTo(ArchiveStatus.UNKNOWN);

        write(NOW.plusSeconds(1), VERIFIED_REPORT, DATASET);

        assertThat(read(NOW).state()).isEqualTo(State.INVALID);
    }

    @Test
    void unavailableDoesNotInventCountsAndBadConfigurationFailsClosed()
            throws Exception {

        assertThat(read(NOW).state()).isEqualTo(State.UNAVAILABLE);

        assertThat(AuditIntegrityReport.read(
                "", "", "", NOW
        ).state()).isEqualTo(State.NOT_CONFIGURED);

        assertThat(AuditIntegrityReport.read(
                report.toString(), DATASET, "", NOW
        ).state()).isEqualTo(State.INVALID);

        write(NOW, UNAVAILABLE_REPORT, DATASET);

        var unavailable = read(NOW);

        assertThat(unavailable.state()).isEqualTo(State.UNAVAILABLE);
        assertThat(unavailable.checkedEvents()).isNull();
        assertThat(unavailable.violations()).isNull();
        assertThat(unavailable.unanchoredEvents()).isNull();
        assertThat(unavailable.archiveStatus())
                .isEqualTo(ArchiveStatus.UNKNOWN);

        Files.writeString(report, "x".repeat(4097));

        assertThat(read(NOW).state()).isEqualTo(State.INVALID);

        Files.delete(report);

        Files.createSymbolicLink(
                report,
                directory.resolve("other")
        );

        assertThat(read(NOW).state()).isEqualTo(State.INVALID);
    }

    @Test
    void expiredUnavailableReportBecomesStale() throws Exception {
        write(
                NOW.minusSeconds(900),
                UNAVAILABLE_REPORT,
                DATASET
        );

        var stale = read(NOW);

        assertThat(stale.state()).isEqualTo(State.STALE);
        assertThat(stale.checkedEvents()).isNull();
        assertThat(stale.violations()).isNull();
        assertThat(stale.unanchoredEvents()).isNull();
        assertThat(stale.archiveStatus()).isEqualTo(ArchiveStatus.UNKNOWN);
    }

    @Test
    void rejectsSignedReportsWithInvalidCounters() throws Exception {
        String[] invalidReports = {
                "VERIFIED\n10000001\n0\n0\nAVAILABLE",
                "VERIFIED\n-1\n0\n0\nAVAILABLE",
                "VERIFIED\n0165\n0\n0\nAVAILABLE",
                "VIOLATION\n1\n2\n0\nAVAILABLE",
                "PENDING\n165\n0\n0\nAVAILABLE",
                "UNAVAILABLE\n0\n0\n0\nUNKNOWN",
                "VERIFIED\n165\n0\n0\nUNKNOWN"
        };

        for (String fields : invalidReports) {
            write(NOW, fields, DATASET);

            assertThat(read(NOW).state())
                    .as("Invalid report: %s", fields)
                    .isEqualTo(State.INVALID);
        }
    }

    @Test
    void rejectsNonAsciiReport() throws Exception {
        write(NOW, VERIFIED_REPORT, DATASET);

        byte[] bytes = Files.readAllBytes(report);

        bytes[0] = (byte) 0xFF;

        Files.write(report, bytes);

        assertThat(read(NOW).state()).isEqualTo(State.INVALID);
    }

    @Test
    void metricReflectsCurrentVerifiedEvidenceWithoutCaching()
            throws Exception {

        var registry = new SimpleMeterRegistry();

        try {
            var service = new AuditIntegrityService(
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    registry,
                    report.toString(),
                    DATASET,
                    publicKey
            );

            var gauge = registry.get(
                    "safeai.audit.integrity.state"
            ).gauge();

            assertThat(gauge.value()).isEqualTo(3.0);

            write(NOW, VERIFIED_REPORT, DATASET);

            assertThat(gauge.value()).isEqualTo(1.0);
            assertThat(service.current().state()).isEqualTo(State.VERIFIED);

            write(NOW, "VIOLATION\n165\n1\n0\nAVAILABLE", DATASET);

            assertThat(gauge.value()).isEqualTo(2.0);

        } finally {
            registry.close();
        }
    }

    private AuditIntegrityStatus read(Instant now) {
        return AuditIntegrityReport.read(
                report.toString(),
                DATASET,
                publicKey,
                now
        );
    }

    private void write(
            Instant checkedAt,
            String fields,
            String dataset
    ) throws Exception {

        String message = "safeai-audit-status-v1\n"
                + dataset + "\n"
                + checkedAt.getEpochSecond() + "\n"
                + fields + "\n";

        Signature signer = Signature.getInstance("Ed25519");

        signer.initSign(key.getPrivate());

        signer.update(message.getBytes(StandardCharsets.US_ASCII));

        Files.writeString(
                report,
                message
                        + Base64.getEncoder().encodeToString(signer.sign())
                        + "\n"
        );
    }
}
