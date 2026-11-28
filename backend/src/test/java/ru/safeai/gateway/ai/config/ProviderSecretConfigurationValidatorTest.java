package ru.safeai.gateway.ai.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("unit")
class ProviderSecretConfigurationValidatorTest {
    private static final String KEY = "sk-fixture-only-0123456789";

    @TempDir
    Path temp;

    @Test
    void parsesExactScalarWithAtMostOneTerminalNewline() {
        assertThat(ProviderSecretFile.parse(bytes(KEY))).isEqualTo(KEY);
        assertThat(ProviderSecretFile.parse(bytes(KEY + "\n"))).isEqualTo(KEY);
        for (String malformed : new String[]{"", "short", "test-not-a-real-provider-key",
                "REPLACE_ME_PROVIDER_SECRET", KEY + "\r\n", KEY + "\n\n",
                " " + KEY, KEY + "\t", KEY + "\0", KEY + " ", KEY + "é", "a".repeat(4097)}) {
            assertThatThrownBy(() -> ProviderSecretFile.parse(bytes(malformed)))
                    .isInstanceOf(IllegalStateException.class).hasNoCause()
                    .hasMessageNotContaining(KEY);
        }
    }

    @Test
    void missingOrSymlinkFilesCannotBeAcceptedEvenWithCorrectBinding() throws Exception {
        assertThatThrownBy(() -> ProviderSecretFile.requireMatches(temp.resolve("missing"), KEY))
                .isInstanceOf(IllegalStateException.class).hasNoCause().hasMessageNotContaining(KEY);
        Path real = Files.writeString(temp.resolve("key"), KEY);
        Path alias = Files.createSymbolicLink(temp.resolve("alias"), real);
        assertThatThrownBy(() -> ProviderSecretFile.requireMatches(alias, KEY))
                .isInstanceOf(IllegalStateException.class).hasNoCause().hasMessageNotContaining(KEY);
    }

    @Test
    void actualFileBindingMustMatchAndPermissionsCannotBeBroadened() throws Exception {
        Path file = Files.writeString(temp.resolve("key"), KEY + "\n");
        String owner = Files.getOwner(file).getName();
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"));
        ProviderSecretFile.requireMatches(file, KEY, owner);
        assertThatThrownBy(() -> ProviderSecretFile.requireMatches(file, KEY + "x", owner))
                .isInstanceOf(IllegalStateException.class).hasNoCause().hasMessageNotContaining(KEY);
        assertThatThrownBy(() -> ProviderSecretFile.requireMatches(file, KEY, owner + "-not-owner"))
                .isInstanceOf(IllegalStateException.class).hasNoCause();
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        assertThatThrownBy(() -> ProviderSecretFile.requireMatches(file, KEY, owner))
                .isInstanceOf(IllegalStateException.class).hasNoCause();
    }

    @Test
    void systemEnvironmentCannotShadowSecretMountEvenIfEmpty() {
        for (String name : Set.of("OPENAI_API_KEY", "ANTHROPIC_API_KEY", "OPENAI_EMBEDDING_API_KEY",
                "KNOWLEDGE_OCR_API_KEY", "SAFEAI_AI_OPENAI_APIKEY", "SAFEAI_AI_ANTHROPIC_API_KEY",
                "SAFEAI_KNOWLEDGE_EMBEDDING_API_KEY", "SAFEAI_KNOWLEDGE_OCR_APIKEY")) {
            assertThatThrownBy(() -> ProviderSecretConfigurationValidator.rejectEnvironmentOverrides(Set.of(name)))
                    .isInstanceOf(IllegalStateException.class).hasNoCause();
        }
        ProviderSecretConfigurationValidator.rejectEnvironmentOverrides(Set.of("OPENAI_MODEL", "PATH"));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
