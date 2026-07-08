package ru.safeai.gateway.common.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CorsPropertiesTest {

    @Test
    void allowedOriginList_shouldReturnEmptyListWhenAllowedOriginsIsNull() {
        CorsProperties properties = new CorsProperties(null);

        assertThat(properties.allowedOriginList()).isEmpty();
    }

    @Test
    void allowedOriginList_shouldReturnEmptyListWhenAllowedOriginsIsBlank() {
        CorsProperties properties = new CorsProperties("   ");

        assertThat(properties.allowedOriginList()).isEmpty();
    }

    @Test
    void allowedOriginList_shouldParseCommaSeparatedOrigins() {
        CorsProperties properties = new CorsProperties(
                "https://app.example.com,https://admin.example.com"
        );

        assertThat(properties.allowedOriginList())
                .containsExactly(
                        "https://app.example.com",
                        "https://admin.example.com"
                );
    }

    @Test
    void allowedOriginList_shouldTrimBlankValuesAndRemoveDuplicates() {
        CorsProperties properties = new CorsProperties(
                " https://app.example.com, ,https://app.example.com, https://admin.example.com "
        );

        assertThat(properties.allowedOriginList())
                .containsExactly(
                        "https://app.example.com",
                        "https://admin.example.com"
                );
    }

    @Test
    void allowedOriginList_shouldNotFallbackToLocalhost() {
        CorsProperties properties = new CorsProperties("");

        assertThat(properties.allowedOriginList())
                .doesNotContain("http://localhost:5173");
    }
}