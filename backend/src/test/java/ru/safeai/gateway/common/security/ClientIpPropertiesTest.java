package ru.safeai.gateway.common.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientIpPropertiesTest {

    @Test
    void constructor_shouldNormalizeValues() {
        ClientIpProperties properties = new ClientIpProperties(List.of(
                " 172.28.0.0/24 ",
                "172.28.0.0/24",
                "127.0.0.1/32"
        ));

        assertThat(properties.trustedProxyCidrs())
                .containsExactly(
                        "172.28.0.0/24",
                        "127.0.0.1/32"
                );
    }

    @Test
    void constructor_shouldUseEmptyListWhenValueIsNull() {
        ClientIpProperties properties = new ClientIpProperties(null);

        assertThat(properties.trustedProxyCidrs()).isEmpty();
    }

    @Test
    void constructor_shouldRejectInvalidCidrImmediately() {
        assertThatThrownBy(() -> new ClientIpProperties(
                List.of("172.28.0.0/24", "not-a-cidr")
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not-a-cidr");
    }
}
