package ru.safeai.gateway.common.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

@Component
public class ClientIpResolver {

    private static final String UNKNOWN = "unknown";

    private static final String X_FORWARDED_FOR = "X-Forwarded-For";
    private static final String X_REAL_IP = "X-Real-IP";

    private static final Pattern IPV4_WITH_PORT =
            Pattern.compile("^(\\d{1,3}(?:\\.\\d{1,3}){3}):\\d+$");

    private static final Pattern SAFE_IP_CHARS =
            Pattern.compile("^[0-9a-fA-F:.]+$");

    private final List<IpAddressMatcher> trustedProxyMatchers;

    public ClientIpResolver(ClientIpProperties properties) {
        Objects.requireNonNull(properties, "properties не должен быть null");

        this.trustedProxyMatchers = properties.trustedProxyCidrs()
                .stream()
                .map(this::toIpMatcher)
                .toList();
    }

    public String resolve(HttpServletRequest request) {
        Objects.requireNonNull(request, "request не должен быть null");

        String remoteAddr = normalizeIp(request.getRemoteAddr())
                .orElse(UNKNOWN);

        if (UNKNOWN.equals(remoteAddr)) {
            return UNKNOWN;
        }

        if (isDirectClient(remoteAddr)) {
            return remoteAddr;
        }

        return resolveFromForwardedHeaders(request)
                .orElse(remoteAddr);
    }

    private Optional<String> resolveFromForwardedHeaders(HttpServletRequest request) {
        Optional<String> fromXForwardedFor = resolveFromXForwardedFor(
                request.getHeader(X_FORWARDED_FOR)
        );

        if (fromXForwardedFor.isPresent()) {
            return fromXForwardedFor;
        }

        return normalizeIp(request.getHeader(X_REAL_IP))
                .filter(this::isDirectClient);
    }

    private Optional<String> resolveFromXForwardedFor(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return Optional.empty();
        }

        List<String> chain = Arrays.stream(headerValue.split(","))
                .map(this::normalizeIp)
                .flatMap(Optional::stream)
                .toList();

        if (chain.isEmpty()) {
            return Optional.empty();
        }

        /*
         * X-Forwarded-For обычно выглядит так:
         *
         * client, proxy1, proxy2
         *
         * Если backend получил запрос от trusted proxy, идем справа налево
         * и берем ближайший direct client, то есть первый адрес, который
         * не входит в список trusted proxies.
         *
         * Это защищает от ситуации, когда клиент сам подставил левый
         * X-Forwarded-For.
         */
        for (int i = chain.size() - 1; i >= 0; i--) {
            String candidate = chain.get(i);

            if (isDirectClient(candidate)) {
                return Optional.of(candidate);
            }
        }

        return Optional.empty();
    }

    private boolean isDirectClient(String ip) {
        return !matchesTrustedProxy(ip);
    }

    private boolean matchesTrustedProxy(String ip) {
        if (ip == null || ip.isBlank() || trustedProxyMatchers.isEmpty()) {
            return false;
        }

        return trustedProxyMatchers.stream()
                .anyMatch(matcher -> matcher.matches(ip));
    }

    private Optional<String> normalizeIp(String rawValue) {
        if (rawValue == null) {
            return Optional.empty();
        }

        String value = rawValue.trim();

        if (value.isBlank() || UNKNOWN.equalsIgnoreCase(value)) {
            return Optional.empty();
        }

        value = stripIpv6Brackets(value);
        value = stripIpv4Port(value);

        if (!SAFE_IP_CHARS.matcher(value).matches()) {
            return Optional.empty();
        }

        try {
            InetAddress address = InetAddress.getByName(value);
            return Optional.of(address.getHostAddress());
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private String stripIpv4Port(String value) {
        var matcher = IPV4_WITH_PORT.matcher(value);

        if (matcher.matches()) {
            return matcher.group(1);
        }

        return value;
    }

    private String stripIpv6Brackets(String value) {
        if (!value.startsWith("[")) {
            return value;
        }

        int closingBracket = value.indexOf(']');

        if (closingBracket > 0) {
            return value.substring(1, closingBracket);
        }

        return value;
    }

    private IpAddressMatcher toIpMatcher(String cidr) {
        try {
            return new IpAddressMatcher(cidr);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Некорректный CIDR в safeai.security.client-ip.trusted-proxy-cidrs: " + cidr,
                    exception
            );
        }
    }
}