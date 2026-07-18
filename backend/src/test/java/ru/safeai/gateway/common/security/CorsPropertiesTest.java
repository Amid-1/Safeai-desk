package ru.safeai.gateway.common.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorsPropertiesTest {

    @Test
    void constructor_shouldReturnEmptyListWhenAllowedOriginsIsNull() {
        CorsProperties properties = new CorsProperties(null);

        assertThat(properties.allowedOrigins()).isEmpty();
        assertThat(properties.allowedOriginList()).isEmpty();
    }

    @Test
    void constructor_shouldTrimBlankValuesAndRemoveDuplicates() {
        CorsProperties properties = new CorsProperties(List.of(
                " https://app.example.com ",
                " ",
                "https://app.example.com",
                "https://admin.example.com"
        ));

        assertThat(properties.allowedOrigins())
                .containsExactly(
                        "https://app.example.com",
                        "https://admin.example.com"
                );

        assertThat(properties.allowedOriginList())
                .containsExactly(
                        "https://app.example.com",
                        "https://admin.example.com"
                );
    }

    @Test
    void constructor_shouldNotFallbackToLocalhost() {
        CorsProperties properties = new CorsProperties(List.of());

        assertThat(properties.allowedOrigins())
                .doesNotContain("http://localhost:5173");
    }

    @Test
    void constructor_shouldRejectWildcard() {
        assertThatThrownBy(() -> new CorsProperties(List.of("*")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("*");
    }

    @Test
    void constructor_shouldRejectOriginWithoutScheme() {
        assertThatThrownBy(() -> new CorsProperties(List.of("example.com")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CORS origin");
    }

    @Test
    void constructor_shouldRejectTrailingSlash() {
        assertThatThrownBy(() -> new CorsProperties(
                List.of("https://example.com/")
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("завершающего");
    }

    @Test
    void constructor_shouldRejectPathQueryFragmentAndUserInfo() {
        assertThatThrownBy(() -> new CorsProperties(
                List.of("https://example.com/path")
        )).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> new CorsProperties(
                List.of("https://example.com?x=1")
        )).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> new CorsProperties(
                List.of("https://example.com#fragment")
        )).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> new CorsProperties(
                List.of("https://user@example.com")
        )).isInstanceOf(IllegalStateException.class);
    }
}
