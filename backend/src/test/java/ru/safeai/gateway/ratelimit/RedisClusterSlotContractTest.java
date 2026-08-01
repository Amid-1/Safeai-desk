package ru.safeai.gateway.ratelimit;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RedisClusterSlotContractTest {

    private static final String SECRET =
            "0123456789abcdef0123456789abcdef";

    @Test
    void allKeysOfEachLuaInvocationShareRedisClusterSlot() {
        RateLimitKeyFactory factory =
                new RateLimitKeyFactory(
                        new RateLimitRedisKeyProperties(
                                "safeai:test",
                                SECRET,
                                "v1"
                        )
                );

        String email =
                factory.loginEmail(
                        "admin@example.com"
                );

        String ip =
                factory.loginIp(
                        "203.0.113.10"
                );

        assertThat(slot(email))
                .isEqualTo(slot(ip))
                .isEqualTo(
                        slot(
                                factory.exceededMarker(
                                        email
                                )
                        )
                )
                .isEqualTo(
                        slot(
                                factory.exceededMarker(
                                        ip
                                )
                        )
                );

        UUID organizationId =
                UUID.fromString(
                        "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
                );

        String user =
                factory.aiMessageUser(
                        organizationId,
                        UUID.fromString(
                                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
                        )
                );

        String organization =
                factory.aiMessageOrganization(
                        organizationId
                );

        assertThat(slot(user))
                .isEqualTo(slot(organization))
                .isEqualTo(
                        slot(
                                factory.exceededMarker(
                                        user
                                )
                        )
                )
                .isEqualTo(
                        slot(
                                factory.exceededMarker(
                                        organization
                                )
                        )
                );
    }

    private int slot(
            String key
    ) {
        byte[] bytes =
                clusterHashInput(key)
                        .getBytes(
                                StandardCharsets.UTF_8
                        );

        int crc = 0;

        for (byte value : bytes) {
            crc ^= (value & 0xff) << 8;

            for (int bit = 0;
                    bit < 8;
                    bit++) {
                crc = (crc & 0x8000) != 0
                        ? (crc << 1) ^ 0x1021
                        : crc << 1;

                crc &= 0xffff;
            }
        }

        return crc % 16_384;
    }

    private String clusterHashInput(
            String key
    ) {
        int open = key.indexOf('{');

        if (open < 0) {
            return key;
        }

        int close = key.indexOf(
                '}',
                open + 1
        );

        if (close <= open + 1) {
            return key;
        }

        return key.substring(
                open + 1,
                close
        );
    }
}
