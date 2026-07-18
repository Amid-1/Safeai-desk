package ru.safeai.gateway.common.security;

import jakarta.servlet.http.HttpServletRequest;
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

@Component
public class ClientIpResolver {

    private static final String UNKNOWN = "unknown";
    private static final String X_FORWARDED_FOR = "X-Forwarded-For";
    private static final String X_REAL_IP = "X-Real-IP";

    private static final int MAX_FORWARDED_HOPS = 32;

    private static final Pattern IPV4_WITH_OPTIONAL_PORT = Pattern.compile(
            "^(\\d{1,3}(?:\\.\\d{1,3}){3})(?::\\d{1,5})?$"
    );

    private static final Pattern BRACKETED_IPV6_WITH_OPTIONAL_PORT = Pattern.compile(
            "^\\[([0-9a-fA-F:.]+)](?::\\d{1,5})?$"
    );

    private static final Pattern IPV6_LITERAL = Pattern.compile("^[0-9a-fA-F:.]+$");

    private final List<IpAddressMatcher> trustedProxyMatchers;

    public ClientIpResolver(ClientIpProperties properties) {
        Objects.requireNonNull(properties, "properties не должен быть null");

        this.trustedProxyMatchers = properties.trustedProxyCidrs()
                .stream()
                .map(IpAddressMatcher::new)
                .toList();
    }

    public String resolve(HttpServletRequest request) {
        Objects.requireNonNull(request, "request не должен быть null");

        String remoteAddr = normalizeIp(request.getRemoteAddr()).orElse(UNKNOWN);

        if (UNKNOWN.equals(remoteAddr) || isDirectClient(remoteAddr)) {
            return remoteAddr;
        }

        return resolveFromForwardedHeaders(request).orElse(remoteAddr);
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

        String[] rawHops = headerValue.split(",", -1);

        /*
         * Не обрабатываем чрезмерную цепочку и не пытаемся частично ей доверять.
         * В таком случае вызывающий код безопасно вернётся к remoteAddr.
         */
        if (rawHops.length > MAX_FORWARDED_HOPS) {
            return Optional.empty();
        }

        List<String> chain = new ArrayList<>(rawHops.length);

        for (String rawHop : rawHops) {
            Optional<String> normalized = normalizeIp(rawHop);

            /*
             * Невалидный элемент делает всю цепочку недоверенной.
             * Иначе удаление плохого hop могло бы изменить смысл цепочки.
             */
            if (normalized.isEmpty()) {
                return Optional.empty();
            }

            chain.add(normalized.get());
        }

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

        Matcher bracketedIpv6 = BRACKETED_IPV6_WITH_OPTIONAL_PORT.matcher(value);
        if (bracketedIpv6.matches()) {
            return normalizeIpv6(bracketedIpv6.group(1));
        }

        Matcher ipv4 = IPV4_WITH_OPTIONAL_PORT.matcher(value);
        if (ipv4.matches()) {
            return normalizeIpv4(ipv4.group(1));
        }

        if (value.indexOf(':') >= 0 && IPV6_LITERAL.matcher(value).matches()) {
            return normalizeIpv6(value);
        }

        /*
         * Любые hostname, включая hex-похожие имена наподобие dead.beef,
         * отклоняются до InetAddress и не инициируют DNS lookup.
         */
        return Optional.empty();
    }

    private Optional<String> normalizeIpv4(String value) {
        String[] octets = value.split("\\.", -1);

        if (octets.length != 4) {
            return Optional.empty();
        }

        for (String octet : octets) {
            try {
                if (octet.isEmpty() || Integer.parseInt(octet) > 255) {
                    return Optional.empty();
                }
            } catch (NumberFormatException exception) {
                return Optional.empty();
            }
        }

        try {
            return Optional.of(InetAddress.getByName(value).getHostAddress());
        } catch (UnknownHostException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private Optional<String> normalizeIpv6(String value) {
        try {
            InetAddress address = InetAddress.getByName(value);

            if (address.getAddress().length != 16) {
                return Optional.empty();
            }

            return Optional.of(address.getHostAddress());
        } catch (UnknownHostException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
