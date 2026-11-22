package ru.safeai.gateway.ai.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;

/** Fixed-size, non-symlink scalar credential. Never includes input in errors. */
public final class ProviderSecretFile {
    private static final int MAX_BYTES = 4096;

    private ProviderSecretFile() {}

    public static void requireMatches(Path path, String configured) {
        requireMatches(path, configured, "root");
    }

    static void requireMatches(Path path, String configured, String expectedOwner) {
        byte[] bytes = null;
        byte[] expected = null;
        byte[] actual = null;
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw invalid();
            // Group read is intentional for the non-root Compose process.
            var permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            if (!permissions.equals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----"))
                    || !Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).getName().equals(expectedOwner)
                    || !Integer.valueOf(1).equals(Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS))) {
                throw invalid();
            }
            try (var stream = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                bytes = stream.readNBytes(MAX_BYTES + 2);
            }
            String key = parse(bytes);
            expected = key.getBytes(StandardCharsets.US_ASCII);
            if (configured == null) throw invalid();
            actual = configured.getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(expected, actual)) throw invalid();
        } catch (IOException | UnsupportedOperationException e) {
            throw invalid();
        } finally {
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
            if (expected != null) Arrays.fill(expected, (byte) 0);
            if (actual != null) Arrays.fill(actual, (byte) 0);
        }
    }

    static String parse(byte[] bytes) {
        int length = bytes.length;
        if (length > 0 && bytes[length - 1] == '\n') length--;
        if (length < 16 || length > MAX_BYTES) throw invalid();
        for (int i = 0; i < length; i++) {
            int c = Byte.toUnsignedInt(bytes[i]);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '='
                    || c == '+' || c == '/' || c == '~')) throw invalid();
        }
        String key = new String(bytes, 0, length, StandardCharsets.US_ASCII);
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.startsWith("replace") || lower.startsWith("change-me")
                || lower.startsWith("changeme") || lower.startsWith("your-")
                || lower.startsWith("test-") || lower.startsWith("dummy-")) throw invalid();
        return key;
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("Production provider credential file is missing, unsafe or does not match binding");
    }
}
