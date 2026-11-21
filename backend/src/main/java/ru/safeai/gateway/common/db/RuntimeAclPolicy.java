package ru.safeai.gateway.common.db;

import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Explicitly reviewed schema boundary.
 * Never derive approval from latest migration.
 */
final class RuntimeAclPolicy {

    static final int REVIEWED_THROUGH = 96;

    static final String ACL_RESOURCE =
            "/db/acl/runtime-v90.sql";

    private static final String MANIFEST =
            "/db/acl/reviewed-migrations.sha256";

    private static final Pattern ENTRY = Pattern.compile(
            "([a-f0-9]{64}) {2}(V([1-9][0-9]*)__[^/\\\\]+\\.sql(?:\\.conf)?)"
    );

    private RuntimeAclPolicy() {
    }

    static Map<Integer, String> verifyReviewedResources()
            throws IOException {

        return verifyReviewedResources(
                RuntimeAclPolicy::readResource
        );
    }

    @FunctionalInterface
    interface ResourceReader {

        byte[] read(String resource) throws IOException;
    }

    static Map<Integer, String> verifyReviewedResources(
            ResourceReader reader
    ) throws IOException {

        // Missing ACL must be detected before any pending SQL executes.
        readRequired(reader, ACL_RESOURCE);

        Map<Integer, String> scripts = new TreeMap<>();
        Map<String, String> hashes = new HashMap<>();

        for (String line : new String(
                readRequired(reader, MANIFEST),
                StandardCharsets.UTF_8
        ).lines().toList()) {

            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }

            var matcher = ENTRY.matcher(line);

            if (!matcher.matches()
                    || hashes.putIfAbsent(
                            matcher.group(2),
                            matcher.group(1)
                    ) != null) {

                throw blocked(
                        "invalid or duplicate manifest entry"
                );
            }

            if (matcher.group(2).endsWith(".sql")
                    && scripts.putIfAbsent(
                            Integer.parseInt(matcher.group(3)),
                            matcher.group(2)
                    ) != null) {

                throw blocked(
                        "duplicate reviewed version"
                );
            }
        }

        if (scripts.size() != REVIEWED_THROUGH) {
            throw blocked("review boundary mismatch");
        }

        for (int version = 1; version <= REVIEWED_THROUGH; version++) {

            String script = scripts.get(version);

            if (script == null) {
                throw blocked(
                        "missing reviewed V" + version
                );
            }

            String config = script + ".conf";

            boolean configPresent =
                    reader.read("/db/migration/" + config) != null;

            if (configPresent != hashes.containsKey(config)) {
                throw blocked(
                        "unreviewed migration configuration: "
                                + config
                );
            }
        }

        for (var item : hashes.entrySet()) {

            if (!scripts.containsValue(
                    item.getKey().replaceFirst("\\.conf$", "")
            )) {

                throw blocked(
                        "orphan migration configuration"
                );
            }

            if (!item.getValue().equals(
                    sha256(
                            readRequired(
                                    reader,
                                    "/db/migration/" + item.getKey()
                            )
                    )
            )) {

                throw blocked(
                        "migration content changed: "
                                + item.getKey()
                );
            }
        }

        return Map.copyOf(scripts);
    }

    static void verifyResolvedMigrations(
            MigrationInfo[] migrations,
            Map<Integer, String> approved
    ) {

        Set<Integer> observed = new HashSet<>();

        for (MigrationInfo migration : migrations) {

            int number = requireVersionNumber(migration);

            if (!observed.add(number)
                    || !migration.getScript().equals(
                            approved.get(number)
                    )) {

                throw blocked(
                        "unreviewed or duplicate migration: "
                                + migration.getScript()
                );
            }
        }

        if (!observed.equals(approved.keySet())) {
            throw blocked(
                    "incomplete migration inventory"
            );
        }
    }

    private static int requireVersionNumber(
            MigrationInfo migration
    ) {

        var migrationVersion = migration.getVersion();
        var migrationState = migration.getState();

        if (migrationVersion == null
                || (migrationState != MigrationState.PENDING
                && migrationState != MigrationState.SUCCESS)) {

            throw blocked(
                    "unresolved or non-versioned migration: "
                            + migration.getScript()
            );
        }

        String version = migrationVersion.getVersion();

        if (!version.matches("[1-9][0-9]*")
                || version.length() > 9) {

            throw blocked(
                    "unexpected version: " + version
            );
        }

        return Integer.parseInt(version);
    }

    static String historyQuery() {

        return """
                SELECT count(*)=%1$d AND count(DISTINCT version)=%1$d
                    AND bool_and(success AND version ~ '^[1-9][0-9]*$' AND version::int BETWEEN 1 AND %1$d)
                FROM public.flyway_schema_history WHERE version IS NOT NULL
                """.formatted(REVIEWED_THROUGH);
    }

    private static byte[] readResource(
            String resource
    ) throws IOException {

        try (var input =
                     RuntimeAclPolicy.class.getResourceAsStream(resource)) {

            return input == null
                    ? null
                    : input.readAllBytes();
        }
    }

    private static byte[] readRequired(
            ResourceReader reader,
            String resource
    ) throws IOException {

        byte[] content = reader.read(resource);

        if (content == null) {
            throw blocked(
                    "missing resource: " + resource
            );
        }

        return content;
    }

    private static String sha256(byte[] bytes) {

        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(bytes)
            );

        } catch (NoSuchAlgorithmException exception) {

            throw new IllegalStateException(
                    "SHA-256 unavailable",
                    exception
            );
        }
    }

    private static IllegalStateException blocked(
            String message
    ) {

        return new IllegalStateException(
                "Runtime ACL review required: " + message
        );
    }
}
