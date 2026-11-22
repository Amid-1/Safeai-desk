package ru.safeai.gateway.ai.provider;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;

/** Pins every physical request to its configured origin and API path. */
final class AiProviderEndpoint {
    private final URI base;
    private final String path;

    private AiProviderEndpoint(URI base) {
        this.base = base;
        this.path = base.getRawPath().replaceAll("/+$", "");
    }

    static AiProviderEndpoint parse(String value) {
        final URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException ignored) {
            throw denied();
        }
        if (!valid(uri)) throw denied();
        // Plain HTTP is exclusively for isolated loopback contract fixtures.
        if (!"https".equalsIgnoreCase(uri.getScheme())
                && !("http".equalsIgnoreCase(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()))) {
            throw denied();
        }
        return new AiProviderEndpoint(uri);
    }

    boolean https() {
        return "https".equalsIgnoreCase(base.getScheme());
    }

    void requireRequest(URI uri) {
        if (!valid(uri) || !base.getScheme().equalsIgnoreCase(uri.getScheme())
                || !base.getHost().equalsIgnoreCase(uri.getHost())
                || effectivePort(base) != effectivePort(uri)
                || !(uri.getRawPath().equals(path) || uri.getRawPath().startsWith(path + "/"))) {
            throw denied();
        }
    }

    private static boolean valid(URI uri) {
        return uri.getHost() != null && uri.getScheme() != null
                && uri.getRawUserInfo() == null && uri.getRawQuery() == null
                && uri.getRawFragment() == null && uri.getRawPath() != null
                && uri.getRawAuthority().equalsIgnoreCase(uri.getHost()
                    + (uri.getPort() == -1 ? "" : ":" + uri.getPort()))
                && uri.getPort() != 0 && uri.getPort() <= 65535
                && uri.getRawPath().matches("/?[A-Za-z0-9/_.~-]*")
                && uri.getRawPath().equals(uri.normalize().getRawPath())
                && !uri.getRawPath().contains("//");
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() != -1 ? uri.getPort()
                : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static IllegalArgumentException denied() {
        return new IllegalArgumentException("AI provider request endpoint is not permitted");
    }

    /** Inspect the address actually passed to Netty connect; no second DNS lookup. */
    static boolean isPublicAddress(SocketAddress remote) {
        if (!(remote instanceof InetSocketAddress socket) || socket.isUnresolved()) return false;
        var address = socket.getAddress();
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        int a = Byte.toUnsignedInt(bytes[0]);
        int b = Byte.toUnsignedInt(bytes[1]);
        int c = Byte.toUnsignedInt(bytes[2]);
        if (bytes.length == 4) {
            return a != 0 && a < 224 && a != 127 && a != 10
                    && !(a == 100 && b >= 64 && b <= 127)
                    && !(a == 169 && b == 254)
                    && !(a == 172 && b >= 16 && b <= 31)
                    && !(a == 192 && (b == 168 || b == 0 && (c == 0 || c == 2) || b == 88 && c == 99))
                    && !(a == 198 && (b == 18 || b == 19 || b == 51 && c == 100))
                    && !(a == 203 && b == 0 && c == 113);
        }
        // Conservative global IPv6 unicast only; exclude protocol-assignment,
        // documentation and 6to4 ranges, as well as mapped/translation addresses.
        return bytes.length == 16 && (a & 0xe0) == 0x20
                && !(a == 0x20 && b == 1 && (c < 2
                    || c == 0x0d && Byte.toUnsignedInt(bytes[3]) == 0xb8))
                && !(a == 0x20 && b == 2)
                && !(a == 0x3f && b == 0xff);
    }
}
