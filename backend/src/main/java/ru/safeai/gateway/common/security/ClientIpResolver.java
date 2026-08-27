package ru.safeai.gateway.common.security;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the direct client IP without trusting spoofable forwarded headers.
 *
 * <p>Forwarded headers are read only when the socket peer is explicitly
 * configured as a trusted reverse proxy.</p>
 *
 * <p>Untrusted hostnames are never sent to DNS resolution. IPv4 is parsed
 * manually; InetAddress is used only for strings that are already recognized
 * as IPv6 literals by the presence of ':'.</p>
 */
@Component
public final class ClientIpResolver {

    private static final String UNKNOWN =
            "unknown";

    private static final int MAX_FORWARDED_HOPS =
            32;

    private static final Pattern IPV4_WITH_PORT =
            Pattern.compile(
                    "^([0-9]{1,3}(?:\\.[0-9]{1,3}){3}):([0-9]{1,5})$"
            );

    private static final Pattern IPV6_LITERAL_CHARS =
            Pattern.compile(
                    "^[0-9A-Fa-f:.]+$"
            );

    private final List<IpAddressMatcher>
            trustedProxyMatchers;

    public ClientIpResolver(
            ClientIpProperties properties
    ) {
        Objects.requireNonNull(
                properties,
                "properties не должен быть null"
        );

        this.trustedProxyMatchers =
                properties
                        .trustedProxyCidrs()
                        .stream()
                        .map(IpAddressMatcher::new)
                        .toList();
    }

    public String resolve(
            HttpServletRequest request
    ) {
        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );

        Optional<String> normalizedRemote =
                normalizeIp(
                        request.getRemoteAddr()
                );

        if (normalizedRemote.isEmpty()) {
            return UNKNOWN;
        }

        String remoteAddress =
                normalizedRemote.get();

        /*
         * Forwarded headers имеют значение только если
         * непосредственный socket peer является доверенным proxy.
         */
        if (isUntrustedAddress(remoteAddress)) {
            return remoteAddress;
        }

        ForwardedChainResult xff =
                parseForwardedFor(
                        request.getHeader(
                                "X-Forwarded-For"
                        )
                );

        if (xff.present()) {
            /*
             * Если X-Forwarded-For присутствует, но malformed,
             * не пытаемся частично доверять цепочке и не переключаемся
             * на X-Real-IP. Возвращаем непосредственный trusted peer.
             */
            if (!xff.valid()) {
                return remoteAddress;
            }

            Optional<String> directClient =
                    nearestUntrustedFromRight(
                            xff.addresses()
                    );

            return directClient.orElse(
                    remoteAddress
            );
        }

        /*
         * X-Real-IP — только fallback при полном отсутствии
         * валидной/невалидной X-Forwarded-For цепочки.
         */
        Optional<String> realIp =
                normalizeIp(
                        request.getHeader(
                                "X-Real-IP"
                        )
                );

        return realIp.orElse(
                remoteAddress
        );
    }

    private ForwardedChainResult parseForwardedFor(
            @Nullable String rawHeader
    ) {
        if (rawHeader == null
                || rawHeader.isBlank()) {

            return ForwardedChainResult
                    .absent();
        }

        String[] rawHops =
                rawHeader.split(
                        ",",
                        -1
                );

        /*
         * String#split для непустой строки всегда возвращает
         * минимум один элемент, поэтому rawHops.length == 0
         * здесь проверять не нужно.
         */
        if (rawHops.length
                > MAX_FORWARDED_HOPS) {

            return ForwardedChainResult
                    .invalid();
        }

        ArrayList<String> addresses =
                new ArrayList<>(
                        rawHops.length
                );

        for (String rawHop : rawHops) {
            Optional<String> address =
                    normalizeIp(rawHop);

            /*
             * Одна malformed запись инвалидирует всю цепочку.
             * Это fail-closed поведение.
             */
            if (address.isEmpty()) {
                return ForwardedChainResult
                        .invalid();
            }

            addresses.add(
                    address.get()
            );
        }

        return ForwardedChainResult.valid(
                List.copyOf(addresses)
        );
    }

    private Optional<String>
    nearestUntrustedFromRight(
            List<String> chain
    ) {
        Objects.requireNonNull(
                chain,
                "chain не должен быть null"
        );

        /*
         * Идём справа налево:
         *
         * client, proxyA, proxyB
         *
         * proxyB расположен ближе всего к нашему backend.
         *
         * Все известные trusted proxy пропускаем.
         * Первый неизвестный адрес считается границей доверия
         * и является resolved client IP.
         */
        for (int index =
                chain.size() - 1;
             index >= 0;
             index--) {

            String candidate =
                    chain.get(index);

            if (isUntrustedAddress(candidate)) {
                return Optional.of(
                        candidate
                );
            }
        }

        return Optional.empty();
    }

    /**
     * Возвращает true, если адрес НЕ входит ни в один
     * явно настроенный trusted proxy CIDR.
     *
     * <p>Пустой allowlist означает, что ни одному адресу
     * не доверяем.</p>
     */
    private boolean isUntrustedAddress(
            String ip
    ) {
        Objects.requireNonNull(
                ip,
                "ip не должен быть null"
        );

        return trustedProxyMatchers
                .stream()
                .noneMatch(
                        matcher ->
                                matcher.matches(ip)
                );
    }

    private Optional<String> normalizeIp(
            @Nullable String rawValue
    ) {
        if (rawValue == null) {
            return Optional.empty();
        }

        String value =
                rawValue.trim();

        if (value.isBlank()
                || UNKNOWN.equalsIgnoreCase(
                value
        )) {
            return Optional.empty();
        }

        value =
                stripBracketedIpv6Port(
                        value
                );

        value =
                stripIpv4Port(
                        value
                );

        Optional<String> ipv4 =
                normalizeIpv4(value);

        if (ipv4.isPresent()) {
            return ipv4;
        }

        /*
         * Если ':' отсутствует, после неудачного IPv4 parse
         * значение не может быть принимаемым нами IP literal.
         *
         * Поэтому hostname вроде:
         *
         * attacker.example.com
         * dead.beef
         *
         * отвергается ДО InetAddress и не вызывает DNS lookup.
         */
        if (!value.contains(":")) {
            return Optional.empty();
        }

        /*
         * Перед InetAddress разрешаем только символы,
         * допустимые в нашем IPv6 literal representation.
         */
        if (!IPV6_LITERAL_CHARS
                .matcher(value)
                .matches()) {

            return Optional.empty();
        }

        try {
            InetAddress address =
                    InetAddress.getByName(
                            value
                    );

            byte[] bytes =
                    address.getAddress();

            /*
             * IPv6 должен занимать ровно 16 байт.
             */
            if (bytes.length != 16) {
                return Optional.empty();
            }

            return Optional.of(
                    address.getHostAddress()
            );
        } catch (
                UnknownHostException exception
        ) {
            return Optional.empty();
        }
    }

    private Optional<String> normalizeIpv4(
            String value
    ) {
        Objects.requireNonNull(
                value,
                "value не должен быть null"
        );

        String[] parts =
                value.split(
                        "\\.",
                        -1
                );

        if (parts.length != 4) {
            return Optional.empty();
        }

        int[] octets =
                new int[4];

        for (int index = 0;
             index < 4;
             index++) {

            String part =
                    parts[index];

            if (part.isEmpty()
                    || part.length() > 3) {

                return Optional.empty();
            }

            for (int charIndex = 0;
                 charIndex < part.length();
                 charIndex++) {

                if (!Character.isDigit(
                        part.charAt(
                                charIndex
                        )
                )) {
                    return Optional.empty();
                }
            }

            int octet;

            try {
                octet =
                        Integer.parseInt(
                                part
                        );
            } catch (
                    NumberFormatException exception
            ) {
                return Optional.empty();
            }

            /*
             * Строка состоит только из decimal digits,
             * поэтому отрицательное значение здесь невозможно.
             */
            if (octet > 255) {
                return Optional.empty();
            }

            octets[index] =
                    octet;
        }

        return Optional.of(
                octets[0]
                        + "."
                        + octets[1]
                        + "."
                        + octets[2]
                        + "."
                        + octets[3]
        );
    }

    private String stripIpv4Port(
            String value
    ) {
        Objects.requireNonNull(
                value,
                "value не должен быть null"
        );

        Matcher matcher =
                IPV4_WITH_PORT
                        .matcher(value);

        if (!matcher.matches()) {
            return value;
        }

        int port;

        try {
            port =
                    Integer.parseInt(
                            matcher.group(2)
                    );
        } catch (
                NumberFormatException exception
        ) {
            return value;
        }

        if (port < 1
                || port > 65535) {

            return value;
        }

        return matcher.group(1);
    }

    private String stripBracketedIpv6Port(
            String value
    ) {
        Objects.requireNonNull(
                value,
                "value не должен быть null"
        );

        if (!value.startsWith("[")) {
            return value;
        }

        int closingBracket =
                value.indexOf(']');

        if (closingBracket <= 1) {
            return value;
        }

        String literal =
                value.substring(
                        1,
                        closingBracket
                );

        /*
         * [2001:db8::1]
         */
        if (closingBracket
                == value.length() - 1) {

            return literal;
        }

        /*
         * После ] разрешён только :port.
         */
        if (value.charAt(
                closingBracket + 1
        ) != ':') {

            return value;
        }

        String rawPort =
                value.substring(
                        closingBracket + 2
                );

        if (rawPort.isEmpty()) {
            return value;
        }

        try {
            int port =
                    Integer.parseInt(
                            rawPort
                    );

            if (port < 1
                    || port > 65535) {

                return value;
            }

            return literal;
        } catch (
                NumberFormatException exception
        ) {
            return value;
        }
    }

    private record ForwardedChainResult(
            boolean present,
            boolean valid,
            List<String> addresses
    ) {

        private ForwardedChainResult {
            addresses =
                    List.copyOf(
                            Objects.requireNonNull(
                                    addresses,
                                    "addresses не должен быть null"
                            )
                    );

            if (!present
                    && !addresses.isEmpty()) {

                throw new IllegalArgumentException(
                        "Absent forwarded chain "
                                + "не может содержать addresses"
                );
            }

            if (!valid
                    && !addresses.isEmpty()) {

                throw new IllegalArgumentException(
                        "Invalid forwarded chain "
                                + "не должна содержать addresses"
                );
            }
        }

        private static ForwardedChainResult absent() {
            return new ForwardedChainResult(
                    false,
                    true,
                    List.of()
            );
        }

        private static ForwardedChainResult invalid() {
            return new ForwardedChainResult(
                    true,
                    false,
                    List.of()
            );
        }

        private static ForwardedChainResult valid(
                List<String> addresses
        ) {
            return new ForwardedChainResult(
                    true,
                    true,
                    addresses
            );
        }
    }
}
