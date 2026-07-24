package ru.safeai.gateway.common.security;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorsPropertiesTest {

    @Test
    void nullAllowedOriginsBecomesEmptyList() {
        CorsProperties properties = new CorsProperties(null);

        assertThat(properties.allowedOrigins()).isEmpty();
    }

    @Test
    void emptyAllowedOriginsRemainsEmpty() {
        CorsProperties properties = new CorsProperties(List.of());

        assertThat(properties.allowedOrigins()).isEmpty();
    }

    @Test
    void nullBlankAndWhitespaceEntriesAreIgnored() {
        List<@Nullable String> source = new ArrayList<>();
        source.add(null);
        source.add("");
        source.add(" ");
        source.add("\t");
        source.add("  https://app.example.com  ");

        CorsProperties properties = new CorsProperties(source);

        assertThat(properties.allowedOrigins())
                .containsExactly("https://app.example.com");
    }

    @Test
    @SuppressWarnings("HttpUrlsUsage")
    void originsAreTrimmedCanonicalizedDeduplicatedAndOrdered() {
        CorsProperties properties = new CorsProperties(List.of(
                " HTTPS://APP.EXAMPLE.COM:443 ",
                "https://app.example.com",
                "HTTP://API.EXAMPLE.COM:80",
                "http://api.example.com",
                "https://admin.example.com"
        ));

        assertThat(properties.allowedOrigins())
                .containsExactly(
                        "https://app.example.com",
                        "http://api.example.com",
                        "https://admin.example.com"
                );
    }

    @Test
    @SuppressWarnings("HttpUrlsUsage")
    void defaultHttpAndHttpsPortsAreRemoved() {
        CorsProperties properties = new CorsProperties(List.of(
                "http://app.example.com:80",
                "https://admin.example.com:443"
        ));

        assertThat(properties.allowedOrigins())
                .containsExactly(
                        "http://app.example.com",
                        "https://admin.example.com"
                );
    }

    @Test
    @SuppressWarnings("HttpUrlsUsage")
    void nonDefaultPortsArePreserved() {
        CorsProperties properties = new CorsProperties(List.of(
                "http://app.example.com:8080",
                "https://admin.example.com:8443"
        ));

        assertThat(properties.allowedOrigins())
                .containsExactly(
                        "http://app.example.com:8080",
                        "https://admin.example.com:8443"
                );
    }

    @Test
    void schemeAndHostAreConvertedToLowercase() {
        CorsProperties properties = new CorsProperties(List.of(
                "HTTPS://APP.EXAMPLE.COM"
        ));

        assertThat(properties.allowedOrigins())
                .containsExactly("https://app.example.com");
    }

    @Test
    void ipv6OriginIsSupportedAndCanonicalized() {
        CorsProperties properties = new CorsProperties(List.of(
                "HTTPS://[2001:DB8::1]:443"
        ));

        assertThat(properties.allowedOrigins())
                .containsExactly("https://[2001:db8::1]");
    }

    @Test
    void localhostAndIpAddressOriginsAreSupported() {
        CorsProperties properties = new CorsProperties(List.of(
                "http://localhost:5173",
                "http://127.0.0.1:8080"
        ));

        assertThat(properties.allowedOrigins())
                .containsExactly(
                        "http://localhost:5173",
                        "http://127.0.0.1:8080"
                );
    }

    @Test
    void resultIsDefensivelyCopied() {
        List<String> source = new ArrayList<>();
        source.add("https://app.example.com");

        CorsProperties properties = new CorsProperties(source);

        source.clear();
        source.add("https://attacker.example.com");

        assertThat(properties.allowedOrigins())
                .containsExactly("https://app.example.com");
    }

    @Test
    void resultIsImmutable() {
        CorsProperties properties = new CorsProperties(List.of(
                "https://app.example.com"
        ));

        assertThatThrownBy(() ->
                properties.allowedOrigins().add(
                        "https://admin.example.com"
                )
        ).isInstanceOf(UnsupportedOperationException.class);

        assertThatThrownBy(() ->
                properties.allowedOrigins().remove(
                        "https://app.example.com"
                )
        ).isInstanceOf(UnsupportedOperationException.class);

        assertThatThrownBy(() ->
                properties.allowedOrigins().clear()
        ).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "*",
            " * "
    })
    void wildcardIsRejected(String origin) {
        assertInvalidOrigin(origin);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://app.example.com/",
            "https://app.example.com/login",
            "https://app.example.com/api/v1"
    })
    void originWithPathIsRejected(String origin) {
        assertInvalidOrigin(origin);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://app.example.com?tenant=1",
            "https://app.example.com#section",
            "https://user@app.example.com"
    })
    void queryFragmentAndUserInfoAreRejected(String origin) {
        assertInvalidOrigin(origin);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://app.example.com",
            "ws://app.example.com",
            "wss://app.example.com",
            "file://app.example.com",
            "mailto:user@example.com"
    })
    void unsupportedSchemesAreRejected(String origin) {
        assertInvalidOrigin(origin);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "app.example.com",
            "//app.example.com",
            "/relative/path",
            "https:app.example.com"
    })
    void originWithoutValidAuthorityIsRejected(String origin) {
        assertInvalidOrigin(origin);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://app.example.com:0",
            "https://app.example.com:65536"
    })
    void invalidPortsAreRejected(String origin) {
        assertInvalidOrigin(origin);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://exa mple.com",
            "https://[invalid-ipv6]",
            "https://",
            "://app.example.com"
    })
    void malformedOriginsAreRejected(String origin) {
        assertInvalidOrigin(origin);
    }

    @Test
    void invalidOriginMessageContainsProblematicValue() {
        String origin = "https://app.example.com/login";

        assertThatThrownBy(() ->
                new CorsProperties(List.of(origin))
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(origin);
    }

    private void assertInvalidOrigin(String origin) {
        assertThatThrownBy(() ->
                new CorsProperties(List.of(origin))
        )
                .isInstanceOf(IllegalStateException.class);
    }
}