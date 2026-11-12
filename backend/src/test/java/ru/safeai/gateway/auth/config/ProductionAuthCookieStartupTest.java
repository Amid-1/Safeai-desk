package ru.safeai.gateway.auth.config;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import ru.safeai.gateway.auth.security.HstsProperties;
import ru.safeai.gateway.auth.service.AuthCookieProperties;
import ru.safeai.gateway.auth.service.AuthCookieService;

import java.time.Duration;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionAuthCookieStartupTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CookieConfiguration.class)
            .withPropertyValues(
                    "safeai.auth.cookies.secure=true",
                    "safeai.auth.cookies.same-site=Lax",
                    "safeai.auth.cookies.access-token-max-age=15m",
                    "safeai.auth.cookies.refresh-token-max-age=7d",
                    "safeai.auth.cookies.refresh-token-absolute-max-age=30d",
                    "safeai.auth.cookies.reuse-detection-retention=7d",
                    "safeai.security.hsts.edge-managed=true"
            );

    @ParameterizedTest
    @CsvSource({"safeai.environment=production", "spring.profiles.active=prod"})
    void productionAcceptsDefaultHostOnlyCookies(String productionSignal) {
        runner.withPropertyValues(productionSignal).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ProductionAuthCookieInvariantValidator.class);
            AuthCookieProperties properties = context.getBean(AuthCookieProperties.class);
            assertThat(properties.csrfTokenName()).isEqualTo("__Host-XSRF-TOKEN");

            MockHttpServletResponse response = new MockHttpServletResponse();
            AuthCookieService cookies = new AuthCookieService(properties);
            cookies.addAccessTokenCookie(response, "test-access");
            cookies.addRefreshTokenCookie(response, "test-refresh", Duration.ofDays(1));
            assertThat(response.getHeaders("Set-Cookie")).hasSize(2).allSatisfy(header ->
                    assertThat(header).contains("Secure", "HttpOnly", "SameSite=Lax")
                            .doesNotContain("Domain="));
            assertThat(Objects.requireNonNull(response.getCookie("__Host-safeai-access")).getPath()).isEqualTo("/");
            assertThat(Objects.requireNonNull(response.getCookie("__Secure-safeai-refresh")).getPath()).isEqualTo("/api/auth");
            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        });
    }

    @ParameterizedTest
    @CsvSource({
            "safeai.auth.cookies.secure=false, secure",
            "safeai.auth.cookies.domain=example.com, domain",
            "safeai.auth.cookies.access-token-name=unsafe-access, access-token cookie",
            "safeai.auth.cookies.refresh-token-name=unsafe-refresh, refresh-token cookie",
            "safeai.auth.cookies.refresh-token-name=__Host-safeai-refresh, Path=/api/auth",
            "safeai.security.hsts.edge-managed=false, HSTS"
    })
    void productionRejectsInvalidCookieBoundary(String property, String message) {
        runner.withPropertyValues("safeai.environment=production", property).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining(message);
        });
    }

    @Test
    void localHttpStillStarts() {
        runner.withPropertyValues("safeai.auth.cookies.secure=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ProductionAuthCookieInvariantValidator.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({AuthCookieProperties.class, HstsProperties.class})
    @Import(ProductionAuthCookieInvariantValidator.class)
    static class CookieConfiguration {
    }
}
