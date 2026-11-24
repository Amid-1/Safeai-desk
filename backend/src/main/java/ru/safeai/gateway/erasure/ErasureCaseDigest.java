// SafeAI-Desk/backend/src/main/java/ru/safeai/gateway/erasure/ErasureCaseDigest.java
package ru.safeai.gateway.erasure;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class ErasureCaseDigest {
    private ErasureCaseDigest() { }
    static String sha(String content) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 недоступен", impossible);
        }
    }
}
