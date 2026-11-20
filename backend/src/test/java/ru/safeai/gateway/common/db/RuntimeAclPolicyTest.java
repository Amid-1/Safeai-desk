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

    private static final String V87_CONF =
            "/db/migration/V87__chat_archive_owner_online_index.sql.conf";

    private static final String V94_CONF =
            V94 + ".conf";

    private static final int NEXT_UNREVIEWED_VERSION =
            RuntimeAclPolicy.REVIEWED_THROUGH + 1;

    @Test
    void acceptsCompleteReviewedMigrationInventory()
            throws IOException {

        var reviewed = RuntimeAclPolicy.verifyReviewedResources();

        assertEquals(
                RuntimeAclPolicy.REVIEWED_THROUGH,
                reviewed.size()
        );

        assertEquals(
                "V94__operation_correlation.sql",
                reviewed.get(94)
        );
    }

    @Test
    void blocksMissingAclOrManifestBeforeMigration() {

        for (String absent : new String[]{
                RuntimeAclPolicy.ACL_RESOURCE,
                MANIFEST,
                V92,
                V94
        }) {
            fails(
                    path -> path.equals(absent)
                            ? null
                            : read(path),
                    "missing resource"
            );
        }
    }

    @Test
    void blocksModifiedMigrationOrConfigAndNewOrMissingConfig() {

        for (String changed : new String[]{
                V92,
                V94,
                V87_CONF,
                V94_CONF
        }) {
            fails(
                    path -> path.equals(changed)
                            ? "changed".getBytes(StandardCharsets.UTF_8)
                            : read(path),
                    "content changed"
            );
        }

        for (String config : new String[]{
                V87_CONF,
                V94_CONF
        }) {
            fails(
                    path -> path.equals(config)
                            ? null
                            : read(path),
                    "unreviewed migration configuration"
            );
        }

        fails(
                path -> path.equals(V92 + ".conf")
                        ? new byte[0]
                        : read(path),
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
                manifest -> manifest
                        + "\n"
                        + findV92Entry(manifest)
                        + "\n",
                "invalid or duplicate"
        );

        manifestFails(
                manifest -> manifest
                        + "\n"
                        + findV92Entry(manifest)
                                .replace("V92__", "V92__duplicate_")
                        + "\n",
                "duplicate reviewed"
        );

        manifestFails(
                manifest -> manifest.replaceAll(
                        "(?m)^.*V92__.*\\R",
                        ""
                ),
                "review boundary mismatch"
        );

        manifestFails(
                manifest -> manifest.replace(
                        "V92__",
                        "V" + NEXT_UNREVIEWED_VERSION + "__"
                ),
                "missing reviewed V92"
        );

        manifestFails(
                manifest -> manifest
                        + "\n"
                        + "0".repeat(64)
                        + "  V"
                        + NEXT_UNREVIEWED_VERSION
                        + "__unreviewed.sql.conf\n",
                "orphan migration configuration"
        );
    }

    private static String findV92Entry(String manifest) {

        return manifest.lines()
                .filter(line -> line.contains("V92__"))
                .findFirst()
                .orElseThrow();
    }

    private static void manifestFails(
            UnaryOperator<String> mutate,
            String message
    ) {

        fails(
                path -> path.equals(MANIFEST)
                        ? mutate.apply(
                                new String(
                                    Objects.requireNonNull(read(path)),
                                        StandardCharsets.UTF_8
                                )
                        ).getBytes(StandardCharsets.UTF_8)
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

        assertTrue(
                error.getMessage().contains(message),
                error.getMessage()
        );
    }

    private static byte[] read(String path) throws IOException {

        try (var input =
                     RuntimeAclPolicyTest.class.getResourceAsStream(path)) {

            return input == null
                    ? null
                    : input.readAllBytes();
        }
    }
}
