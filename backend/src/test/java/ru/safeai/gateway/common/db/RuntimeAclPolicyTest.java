package ru.safeai.gateway.common.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeAclPolicyTest {

    private static final String MANIFEST =
            "/db/acl/reviewed-migrations.sha256";
    private static final String V92 =
            "/db/migration/V92__organization_model_policy_budget_finite_values.sql";
    private static final String V94 =
            "/db/migration/V94__operation_correlation.sql";
    private static final String V95 =
            "/db/migration/V95__chat_quota_physical_attempt_gate.sql";
    private static final String V96 =
            "/db/migration/V96__chat_quota_admission_fence.sql";
    private static final String V97 = "/db/migration/V97__usage_physical_tenant_index.sql";
    private static final String V98 = "/db/migration/V98__usage_export_jobs.sql";
    private static final String V99 = "/db/migration/V99__usage_export_job_guard.sql";
    private static final String V100 = "/db/migration/V100__model_physical_dispatch_admission.sql";
    private static final String V101 = "/db/migration/V101__model_commitment_ledger_lookup.sql";
    private static final String V102 = "/db/migration/V102__platform_erasure_admission_policy.sql";
    private static final String V103 = "/db/migration/V103__erasure_case_intake.sql";
    private static final String V104 = "/db/migration/V104__erasure_actor_detachment_guards.sql";
    private static final String V87_CONF =
            "/db/migration/V87__chat_archive_owner_online_index.sql.conf";
    private static final String V94_CONF = V94 + ".conf";
    private static final String V95_CONF = V95 + ".conf";
    private static final String V96_CONF = V96 + ".conf";
    private static final int NEXT_UNREVIEWED_VERSION =
            RuntimeAclPolicy.REVIEWED_THROUGH + 1;

    @Test
    void acceptsCompleteReviewedMigrationInventory() throws IOException {
        var reviewed = RuntimeAclPolicy.verifyReviewedResources();

        assertEquals(104, reviewed.size());
        assertEquals(RuntimeAclPolicy.REVIEWED_THROUGH, reviewed.size());
        assertEquals("V94__operation_correlation.sql", reviewed.get(94));
        assertEquals("V95__chat_quota_physical_attempt_gate.sql", reviewed.get(95));
        assertEquals("V96__chat_quota_admission_fence.sql", reviewed.get(96));
        assertEquals("V97__usage_physical_tenant_index.sql", reviewed.get(97));
        assertEquals("V98__usage_export_jobs.sql", reviewed.get(98));
        assertEquals("V99__usage_export_job_guard.sql", reviewed.get(99));
        assertEquals("V100__model_physical_dispatch_admission.sql", reviewed.get(100));
        assertEquals("V101__model_commitment_ledger_lookup.sql", reviewed.get(101));
        assertEquals("V102__platform_erasure_admission_policy.sql", reviewed.get(102));
        assertEquals("V103__erasure_case_intake.sql", reviewed.get(103));
        assertEquals("V104__erasure_actor_detachment_guards.sql", reviewed.get(104));
    }

    @Test
    void blocksMissingAclOrManifestBeforeMigration() {
        for (String absent : new String[]{
                RuntimeAclPolicy.ACL_RESOURCE, MANIFEST, V92, V94, V95, V96, V97, V98, V99, V100, V101, V102, V103, V104
        }) {
            fails(
                    path -> path.equals(absent) ? null : read(path),
                    "missing resource"
            );
        }
    }

    @Test
    void blocksModifiedMigrationOrConfigAndNewOrMissingConfig() {
        for (String changed : new String[]{
                V92, V94, V95, V96, V97, V98, V99, V87_CONF, V94_CONF, V95_CONF, V96_CONF,
                V97 + ".conf", V98 + ".conf", V99 + ".conf", V100, V100 + ".conf", V101, V101 + ".conf", V102, V103, V104
        }) {
            fails(
                    path -> path.equals(changed)
                            ? "changed".getBytes(StandardCharsets.UTF_8)
                            : read(path),
                    "content changed"
            );
        }

        for (String config : new String[]{V87_CONF, V94_CONF, V95_CONF, V96_CONF,
                V97 + ".conf", V98 + ".conf", V99 + ".conf", V100 + ".conf", V101 + ".conf"}) {
            fails(
                    path -> path.equals(config) ? null : read(path),
                    "unreviewed migration configuration"
            );
        }

        fails(
                path -> path.equals(V92 + ".conf") ? new byte[0] : read(path),
                "unreviewed migration configuration"
        );
    }

    @Test
    void blocksCorruptDuplicateIncompleteOrNonContiguousManifest() {
        manifestFails(
                manifest -> manifest + "\ninvalid\n",
                "invalid or duplicate"
        );
        manifestFails(
                manifest -> manifest + "\n" + findV92Entry(manifest) + "\n",
                "invalid or duplicate"
        );
        manifestFails(
                manifest -> manifest + "\n"
                        + findV92Entry(manifest).replace("V92__", "V92__duplicate_") + "\n",
                "duplicate reviewed"
        );
        manifestFails(
                manifest -> manifest.replaceAll("(?m)^.*V92__.*\\R", ""),
                "review boundary mismatch"
        );
        manifestFails(
                manifest -> manifest.replace("V92__", "V" + NEXT_UNREVIEWED_VERSION + "__"),
                "missing reviewed V92"
        );
        manifestFails(
                manifest -> manifest + "\n" + "0".repeat(64)
                        + "  V" + NEXT_UNREVIEWED_VERSION + "__unreviewed.sql.conf\n",
                "orphan migration configuration"
        );
    }

    private static String findV92Entry(String manifest) {
        return manifest.lines()
                .filter(line -> line.contains("V92__"))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void blocksPreviousBoundaryAndUnreviewedNextVersionEvenInManifest() {
        manifestFails(
                manifest -> manifest.replaceAll("(?m)^.*V101__.*\\R", ""),
                "review boundary mismatch"
        );
        manifestFails(
                manifest -> manifest + "\n" + "0".repeat(64)
                        + "  V" + NEXT_UNREVIEWED_VERSION + "__unreviewed.sql\n",
                "review boundary mismatch"
        );
        manifestFails(
                manifest -> manifest.replace("V101__", "V" + NEXT_UNREVIEWED_VERSION + "__"),
                "missing reviewed V101"
        );
    }

    private static void manifestFails(
            UnaryOperator<String> mutate,
            String message
    ) {
        fails(
                path -> path.equals(MANIFEST)
                        ? mutate.apply(new String(
                                Objects.requireNonNull(read(path)), StandardCharsets.UTF_8
                        )).getBytes(StandardCharsets.UTF_8)
                        : read(path),
                message
        );
    }

    private static void fails(
            RuntimeAclPolicy.ResourceReader reader,
            String message
    ) {
        var error = assertThrows(
                IllegalStateException.class,
                () -> RuntimeAclPolicy.verifyReviewedResources(reader)
        );

        assertTrue(error.getMessage().contains(message), error.getMessage());
    }

    private static byte[] read(String path) throws IOException {
        try (var input = RuntimeAclPolicyTest.class.getResourceAsStream(path)) {
            return input == null ? null : input.readAllBytes();
        }
    }
}
