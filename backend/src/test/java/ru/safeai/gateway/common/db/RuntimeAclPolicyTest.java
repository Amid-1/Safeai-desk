package ru.safeai.gateway.common.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeAclPolicyTest {
    private static final String MANIFEST = "/db/acl/reviewed-migrations.sha256";
    private static final String V92 = "/db/migration/V92__organization_model_policy_budget_finite_values.sql";
    private static final String V87_CONF = "/db/migration/V87__chat_archive_owner_online_index.sql.conf";

    @Test
    void blocksMissingAclOrManifestBeforeMigration() {
        for (String absent : new String[]{RuntimeAclPolicy.ACL_RESOURCE, MANIFEST, V92}) {
            fails(path -> path.equals(absent) ? null : read(path), "missing resource");
        }
    }

    @Test
    void blocksModifiedMigrationOrConfigAndNewOrMissingConfig() {
        for (String changed : new String[]{V92, V87_CONF}) {
            fails(path -> path.equals(changed) ? "changed".getBytes(StandardCharsets.UTF_8) : read(path), "content changed");
        }
        fails(path -> path.equals(V87_CONF) ? null : read(path), "unreviewed migration configuration");
        fails(path -> path.equals(V92 + ".conf") ? new byte[0] : read(path), "unreviewed migration configuration");
    }

    @Test
    void blocksCorruptDuplicateIncompleteOrNonContiguousManifest() {
        manifestFails(s -> s + "\ninvalid\n", "invalid or duplicate");
        manifestFails(s -> s + s.lines().filter(line -> line.contains("V92__")).findFirst().orElseThrow() + "\n", "invalid or duplicate");
        manifestFails(s -> s + s.lines().filter(line -> line.contains("V92__")).findFirst().orElseThrow().replace("V92__", "V92__duplicate_") + "\n", "duplicate reviewed");
        manifestFails(s -> s.replaceAll("(?m)^.*V92__.*\\R", ""), "review boundary mismatch");
        manifestFails(s -> s.replace("V92__", "V94__"), "missing reviewed V92");
        manifestFails(s -> s + "0".repeat(64) + "  V94__unreviewed.sql.conf\n", "orphan migration configuration");
    }

    private static void manifestFails(UnaryOperator<String> mutate, String message) {
        fails(path -> path.equals(MANIFEST)
                ? mutate.apply(new String(read(path), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8)
                : read(path), message);
    }

    private static void fails(RuntimeAclPolicy.ResourceReader reader, String message) {
        var error = assertThrows(IllegalStateException.class, () -> RuntimeAclPolicy.verifyReviewedResources(reader));
        assertTrue(error.getMessage().contains(message), error.getMessage());
    }

    private static byte[] read(String path) throws IOException {
        try (var in = RuntimeAclPolicyTest.class.getResourceAsStream(path)) {
            return in == null ? null : in.readAllBytes();
        }
    }
}
