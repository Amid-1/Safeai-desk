package ru.safeai.gateway.audit.service;

import org.springframework.stereotype.Component;
import ru.safeai.gateway.audit.config.AuditDetailsProperties;
import ru.safeai.gateway.common.validation.UnicodeValidationSupport;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Audit payload admission + size sanitizer.
 *
 * <p>Two modes deliberately exist:</p>
 *
 * <ul>
 *   <li>typed details: caller uses a dedicated AuditDetails implementation and
 *       arbitrary safe metadata strings are preserved after secret-pattern and
 *       sensitive-key checks;</li>
 *   <li>unstructured map: string values are accepted only for an explicit
 *       metadata-key allowlist. Unknown textual fields are replaced by a
 *       fixed marker instead of trusting a denylist.</li>
 * </ul>
 */
@Component
public final class AuditDetailsSanitizer {

    private static final String REDACTED_VALUE =
            "[REDACTED]";

    private static final String OMITTED_UNAPPROVED_TEXT =
            "[OMITTED_UNAPPROVED_TEXT]";

    private static final String MAX_DEPTH_VALUE =
            "[MAX_DEPTH]";

    private static final String BUDGET_EXCEEDED_VALUE =
            "[BUDGET_EXCEEDED]";

    private static final String NON_FINITE_NUMBER_VALUE =
            "[NON_FINITE_NUMBER]";

    private static final String NUMBER_TOO_LARGE_VALUE =
            "[NUMBER_TOO_LARGE]";

    private static final String UNSUPPORTED_VALUE_PREFIX =
            "[UNSUPPORTED_TYPE:";

    private static final Set<String> SENSITIVE_KEYS =
            Set.of(
                    "password",
                    "passwordhash",
                    "currentpassword",
                    "newpassword",
                    "accesstoken",
                    "refreshtoken",
                    "rawtoken",
                    "jwttoken",
                    "jwt",
                    "bearer",
                    "bearertoken",
                    "authorization",
                    "proxyauthorization",
                    "cookie",
                    "setcookie",
                    "apikey",
                    "xapikey",
                    "secret",
                    "apisecret",
                    "tokensecret",
                    "accesssecret",
                    "clientsecret",
                    "clientkey",
                    "secretkey",
                    "signingkey",
                    "signingsecret",
                    "encryptionkey",
                    "webhooksecret",
                    "xauthtoken",
                    "credential",
                    "credentials",
                    "passphrase",
                    "privatekey",
                    "prompt",
                    "promptcontent",
                    "rawprompt",
                    "response",
                    "responsebody",
                    "responsecontent",
                    "rawresponse",
                    "requestbody",
                    "requestcontent",
                    "rawrequest",
                    "rawvalue",
                    "payload",
                    "body",
                    "headers",
                    "httpheaders",
                    "sessioncredential",
                    "sessionsecret",
                    "authheader",
                    "authheadervalue"
            );

    /**
     * String fields allowed from legacy/unstructured Map callers.
     *
     * <p>These names describe identifiers, enum-like states, provider/model
     * metadata or timestamps. Free-form user/provider bodies are intentionally
     * absent.</p>
     */
    private static final Set<String> SAFE_UNSTRUCTURED_TEXT_KEYS =
            Set.of(
                    "provider",
                    "model",
                    "modelkey",
                    "requestedmodel",
                    "resolvedmodel",
                    "providerrequestid",
                    "providermessageid",
                    "providererrortype",
                    "failurecode",
                    "turnstate",
                    "state",
                    "status",
                    "outcome",
                    "reason",
                    "source",
                    "mode",
                    "role",
                    "lifecycle",
                    "usagestatus",
                    "pricingstatus",
                    "currency",
                    "pricingversion",
                    "finishreason",
                    "airesponsestatus",
                    "responsestatus",
                    "type",
                    "window",
                    "retentionmode",
                    "previoussource",
                    "newsource",
                    "archivedat",
                    "requestid",
                    "contenttype",
                    "documenttype",
                    "capability",
                    "modality",
                    "operation",
                    "policy",
                    "enforcement",
                    "lexicalprofile"
            );

    private static final Pattern JWT_PATTERN =
            Pattern.compile(
                    "(?i)\\beyJ[A-Za-z0-9_-]{8,}\\."
                            + "[A-Za-z0-9_-]{8,}\\."
                            + "[A-Za-z0-9_-]{8,}\\b"
            );

    private static final Pattern AUTH_SCHEME_PATTERN =
            Pattern.compile(
                    "(?i)\\b(?:bearer|basic)\\s+\\S+"
            );

    private static final Pattern PEM_PATTERN =
            Pattern.compile(
                    "-----BEGIN [A-Z0-9 ]*"
                            + "(?:PRIVATE KEY|SECRET)"
                            + "[A-Z0-9 ]*-----"
            );

    private static final Pattern COMMON_SECRET_PREFIX_PATTERN =
            Pattern.compile(
                    "(?i)(?:^|\\s)(?:sk-|sk_live_|rk_live_|"
                            + "ghp_|github_pat_|xox[baprs]-|AKIA)"
                            + "[A-Za-z0-9_\\-]{8,}"
            );

    private final JsonMapper jsonMapper;
    private final AuditDetailsProperties properties;

    public AuditDetailsSanitizer(
            JsonMapper jsonMapper,
            AuditDetailsProperties properties
    ) {
        this.jsonMapper =
                Objects.requireNonNull(
                        jsonMapper,
                        "jsonMapper не должен быть null"
                );

        this.properties =
                Objects.requireNonNull(
                        properties,
                        "properties не должен быть null"
                );
    }

    /**
     * Legacy/unstructured boundary. Arbitrary string fields are not trusted.
     */
    public Map<String, Object> sanitize(
            Map<String, Object> details
    ) {
        return sanitize(
                details,
                TrustMode.UNSTRUCTURED
        );
    }

    /**
     * Typed AuditDetails boundary. The DTO itself is the field allowlist;
     * this sanitizer remains the second line of defence for secrets/size.
     */
    public Map<String, Object> sanitizeTyped(
            Map<String, Object> details
    ) {
        return sanitize(
                details,
                TrustMode.TYPED
        );
    }

    private Map<String, Object> sanitize(
            Map<String, Object> details,
            TrustMode trustMode
    ) {
        Objects.requireNonNull(
                trustMode,
                "trustMode не должен быть null"
        );

        if (details == null
                || details.isEmpty()) {
            return Map.of();
        }

        Budget budget =
                new Budget(
                        properties.maxTotalNodes(),
                        properties.maxTotalStringChars(),
                        Math.max(
                                1_024,
                                properties.maxJsonBytes()
                                        - 4_096
                        )
                );

        if (budget.reserveNode()
                == NodeReservation.EXHAUSTED) {
            return Map.of(
                    "_budgetExceeded",
                    true
            );
        }

        Map<String, Object> sanitized =
                sanitizeMapValue(
                        details,
                        0,
                        budget,
                        trustMode
                );

        int serializedBytes =
                serializedSize(
                        sanitized
                );

        if (serializedBytes
                <= properties.maxJsonBytes()) {
            return sanitized;
        }

        return Map.of(
                "_truncated",
                true,
                "_reason",
                "MAX_JSON_BYTES",
                "_sanitizedBytes",
                serializedBytes
        );
    }

    private Object sanitizeValue(
            Object value,
            int depth,
            Budget budget,
            TrustMode trustMode,
            String parentKey
    ) {
        if (value == null) {
            return null;
        }

        if (budget.reserveNode()
                == NodeReservation.EXHAUSTED) {
            return BUDGET_EXCEEDED_VALUE;
        }

        if (depth
                >= properties.maxDepth()) {
            return MAX_DEPTH_VALUE;
        }

        return switch (value) {
            case String stringValue ->
                    sanitizeString(
                            stringValue,
                            budget,
                            trustMode,
                            parentKey
                    );

            case Number numberValue ->
                    sanitizeNumber(
                            numberValue
                    );

            case Boolean booleanValue ->
                    booleanValue;

            case Character characterValue ->
                    sanitizeString(
                            characterValue.toString(),
                            budget,
                            trustMode,
                            parentKey
                    );

            case UUID uuid ->
                    uuid.toString();

            case Enum<?> enumValue ->
                    enumValue.name();

            case TemporalAccessor temporalAccessor ->
                    consumeSafeText(
                            temporalAccessor.toString(),
                            budget
                    );

            case Map<?, ?> mapValue ->
                    sanitizeMapValue(
                            mapValue,
                            depth + 1,
                            budget,
                            trustMode
                    );

            case Iterable<?> iterableValue ->
                    sanitizeIterableValue(
                            iterableValue,
                            depth + 1,
                            budget,
                            trustMode,
                            parentKey
                    );

            default ->
                    sanitizeUnknownValue(
                            value,
                            depth,
                            budget,
                            trustMode,
                            parentKey
                    );
        };
    }

    private Object sanitizeUnknownValue(
            Object value,
            int depth,
            Budget budget,
            TrustMode trustMode,
            String parentKey
    ) {
        if (value.getClass()
                .isArray()) {
            return sanitizeArrayValue(
                    value,
                    depth + 1,
                    budget,
                    trustMode,
                    parentKey
            );
        }

        return UNSUPPORTED_VALUE_PREFIX
                + value.getClass()
                .getName()
                + "]";
    }

    private Map<String, Object> sanitizeMapValue(
            Map<?, ?> source,
            int depth,
            Budget budget,
            TrustMode trustMode
    ) {
        Map<String, Object> sanitized =
                new LinkedHashMap<>();

        int visited = 0;

        for (Map.Entry<?, ?> entry
                : source.entrySet()) {

            if (visited
                    >= properties.maxContainerItems()) {
                sanitized.putIfAbsent(
                        "_truncated",
                        true
                );
                break;
            }

            visited++;

            String key =
                    sanitizeMapKey(
                            entry.getKey()
                    );

            if (key == null) {
                continue;
            }

            if (budget.reserveText(
                    key
            ) == TextReservation.EXHAUSTED) {
                sanitized.putIfAbsent(
                        "_budgetExceeded",
                        true
                );
                break;
            }

            Object value =
                    isSensitiveKey(
                            key
                    )
                            ? REDACTED_VALUE
                            : sanitizeValue(
                                    entry.getValue(),
                                    depth,
                                    budget,
                                    trustMode,
                                    key
                            );

            if (value != null) {
                /*
                 * Normalization/truncation may collapse two source keys into
                 * one key. Keep the first admitted value; a later entry must
                 * not silently overwrite already admitted audit evidence.
                 */
                sanitized.putIfAbsent(
                        key,
                        value
                );
            }

            if (budget.isExhausted()) {
                sanitized.putIfAbsent(
                        "_budgetExceeded",
                        true
                );
                break;
            }
        }

        return Collections.unmodifiableMap(
                sanitized
        );
    }

    private List<Object> sanitizeIterableValue(
            Iterable<?> source,
            int depth,
            Budget budget,
            TrustMode trustMode,
            String parentKey
    ) {
        List<Object> sanitized =
                new ArrayList<>();

        int visited = 0;

        for (Object item : source) {
            if (visited
                    >= properties.maxContainerItems()) {
                sanitized.add(
                        "_truncated"
                );
                break;
            }

            visited++;

            if (appendSanitizedElement(
                    sanitized,
                    item,
                    depth,
                    budget,
                    trustMode,
                    parentKey
            ) == SequenceStep.STOP) {
                break;
            }
        }

        return List.copyOf(
                sanitized
        );
    }

    private List<Object> sanitizeArrayValue(
            Object source,
            int depth,
            Budget budget,
            TrustMode trustMode,
            String parentKey
    ) {
        List<Object> sanitized =
                new ArrayList<>();

        int length =
                Array.getLength(
                        source
                );

        int limit =
                Math.min(
                        length,
                        properties.maxContainerItems()
                );

        for (int index = 0;
             index < limit;
             index++) {

            if (appendSanitizedElement(
                    sanitized,
                    Array.get(
                            source,
                            index
                    ),
                    depth,
                    budget,
                    trustMode,
                    parentKey
            ) == SequenceStep.STOP) {
                break;
            }
        }

        if (length
                > properties.maxContainerItems()
                && !budget.isExhausted()) {
            sanitized.add(
                    "_truncated"
            );
        }

        return List.copyOf(
                sanitized
        );
    }

    /**
     * Shared sequence element path for Iterable and Java arrays.
     * This intentionally removes the duplicated sanitize/add/budget fragment.
     */
    private SequenceStep appendSanitizedElement(
            List<Object> target,
            Object sourceValue,
            int depth,
            Budget budget,
            TrustMode trustMode,
            String parentKey
    ) {
        Object value =
                sanitizeValue(
                        sourceValue,
                        depth,
                        budget,
                        trustMode,
                        parentKey
                );

        if (budget.isExhausted()) {
            if (value != null
                    && !BUDGET_EXCEEDED_VALUE.equals(
                    value
            )) {
                target.add(
                        value
                );
            }

            if (target.isEmpty()
                    || !BUDGET_EXCEEDED_VALUE.equals(
                    target.getLast(
                    )
            )) {
                target.add(
                        BUDGET_EXCEEDED_VALUE
                );
            }

            return SequenceStep.STOP;
        }

        if (value != null) {
            target.add(
                    value
            );
        }

        return SequenceStep.CONTINUE;
    }

    private String sanitizeString(
            String value,
            Budget budget,
            TrustMode trustMode,
            String parentKey
    ) {
        String normalized =
                normalizeAndTruncateText(
                        value
                );

        if (normalized == null) {
            return null;
        }

        if (looksLikeCredential(
                normalized
        )) {
            return REDACTED_VALUE;
        }

        if (trustMode
                == TrustMode.UNSTRUCTURED
                && isUnapprovedUnstructuredTextKey(
                parentKey
        )) {
            return OMITTED_UNAPPROVED_TEXT;
        }

        return budget.consumeString(
                normalized
        );
    }

    private String consumeSafeText(
            String value,
            Budget budget
    ) {
        String normalized =
                normalizeAndTruncateText(
                        value
                );

        if (normalized == null) {
            return null;
        }

        if (looksLikeCredential(
                normalized
        )) {
            return REDACTED_VALUE;
        }

        return budget.consumeString(
                normalized
        );
    }

    private Object sanitizeNumber(
            Number value
    ) {
        if (value instanceof Double doubleValue) {
            return Double.isFinite(
                    doubleValue
            )
                    ? doubleValue
                    : NON_FINITE_NUMBER_VALUE;
        }

        if (value instanceof Float floatValue) {
            return Float.isFinite(
                    floatValue
            )
                    ? floatValue
                    : NON_FINITE_NUMBER_VALUE;
        }

        if (value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long) {
            return value;
        }

        if (value
                instanceof AtomicInteger atomicInteger) {
            return atomicInteger.get();
        }

        if (value
                instanceof AtomicLong atomicLong) {
            return atomicLong.get();
        }

        if (value
                instanceof BigInteger bigInteger) {
            return bigInteger.toString()
                    .length()
                    <= properties.maxNumberTextLength()
                    ? bigInteger
                    : NUMBER_TOO_LARGE_VALUE;
        }

        if (value
                instanceof BigDecimal bigDecimal) {
            return isSafeDecimal(
                    bigDecimal
            )
                    ? bigDecimal
                    : NUMBER_TOO_LARGE_VALUE;
        }

        String text =
                value.toString();

        if (text.length()
                > properties.maxNumberTextLength()) {
            return NUMBER_TOO_LARGE_VALUE;
        }

        try {
            BigDecimal decimal =
                    new BigDecimal(
                            text
                    );

            return isSafeDecimal(
                    decimal
            )
                    ? decimal
                    : NUMBER_TOO_LARGE_VALUE;
        } catch (NumberFormatException ignored) {
            return UNSUPPORTED_VALUE_PREFIX
                    + value.getClass()
                    .getName()
                    + "]";
        }
    }

    private boolean isSafeDecimal(
            BigDecimal value
    ) {
        int limit =
                properties.maxNumberTextLength();

        return value.toString()
                .length()
                <= limit
                && value.precision()
                <= limit
                && Math.abs(
                (long) value.scale()
        ) <= limit;
    }

    private String sanitizeMapKey(
            Object rawKey
    ) {
        if (rawKey == null) {
            return null;
        }

        String text =
                switch (rawKey) {
                    case String value ->
                            value;

                    case UUID value ->
                            value.toString();

                    case Enum<?> value ->
                            value.name();

                    case Number value ->
                            value.toString();

                    case Boolean value ->
                            value.toString();

                    case Character value ->
                            value.toString();

                    default ->
                            null;
                };

        if (text == null
                || text.isBlank()) {
            return null;
        }

        String normalized =
                normalizeStrictUnicode(
                        text,
                        "audit details key"
                );

        for (int offset = 0;
             offset < normalized.length();) {

            int codePoint =
                    normalized.codePointAt(
                            offset
                    );

            if (Character.isISOControl(
                    codePoint
            )) {
                throw new IllegalArgumentException(
                        "Audit details key содержит управляющий символ"
                );
            }

            offset +=
                    Character.charCount(
                            codePoint
                    );
        }

        normalized =
                normalized.trim();

        if (normalized.isBlank()) {
            return null;
        }

        return truncateByCodePoints(
                normalized,
                properties.maxStringLength()
        );
    }

    private String normalizeAndTruncateText(
            String value
    ) {
        if (value == null
                || value.isBlank()) {
            return null;
        }

        String normalized =
                normalizeStrictUnicode(
                        value,
                        "audit details value"
                )
                        .trim();

        if (normalized.isBlank()) {
            return null;
        }

        return truncateByCodePoints(
                normalized,
                properties.maxStringLength()
        );
    }

    /**
     * Audit payload canonicalization intentionally uses NFKC.
     *
     * <p>Only UTF-16 structural validation is shared with other modules.
     * The normalization form remains an audit-sanitizer decision.</p>
     */
    private static String normalizeStrictUnicode(
            String value,
            String field
    ) {
        UnicodeValidationSupport
                .requireWellFormedUtf16(
                        value,
                        field
                );

        return Normalizer.normalize(
                value,
                Normalizer.Form.NFKC
        );
    }

    private static String truncateByCodePoints(
            String value,
            int maxCodePoints
    ) {
        int count =
                value.codePointCount(
                        0,
                        value.length()
                );

        if (count
                <= maxCodePoints) {
            return value;
        }

        int end =
                value.offsetByCodePoints(
                        0,
                        maxCodePoints
                );

        return value.substring(
                0,
                end
        );
    }

    private static boolean isSensitiveKey(
            String key
    ) {
        String whole =
                canonicalizeKey(
                        key
                );

        if (SENSITIVE_KEYS.contains(
                whole
        )) {
            return true;
        }

        String[] segments =
                key.split(
                        "[\\p{P}\\p{Z}\\s]+"
                );

        for (String segment : segments) {
            if (SENSITIVE_KEYS.contains(
                    canonicalizeKey(
                            segment
                    )
            )) {
                return true;
            }
        }

        return false;
    }

    private static boolean isUnapprovedUnstructuredTextKey(
            String key
    ) {
        return key == null
                || !SAFE_UNSTRUCTURED_TEXT_KEYS.contains(
                canonicalizeKey(
                        key
                )
        );
    }

    private static String canonicalizeKey(
            String value
    ) {
        String normalized =
                Normalizer.normalize(
                                value,
                                Normalizer.Form.NFKC
                        )
                        .toLowerCase(
                                Locale.ROOT
                        );

        StringBuilder result =
                new StringBuilder();

        normalized.codePoints()
                .filter(
                        Character::isLetterOrDigit
                )
                .forEach(
                        result::appendCodePoint
                );

        return result.toString();
    }

    private static boolean looksLikeCredential(
            String value
    ) {
        return JWT_PATTERN
                .matcher(
                        value
                )
                .find()
                || AUTH_SCHEME_PATTERN
                .matcher(
                        value
                )
                .find()
                || PEM_PATTERN
                .matcher(
                        value
                )
                .find()
                || COMMON_SECRET_PREFIX_PATTERN
                .matcher(
                        value
                )
                .find();
    }

    private int serializedSize(
            Map<String, Object> details
    ) {
        try {
            return jsonMapper
                    .writeValueAsBytes(
                            details
                    )
                    .length;
        } catch (JacksonException exception) {
            throw new IllegalArgumentException(
                    "Sanitized audit details "
                            + "не сериализуется в JSON",
                    exception
            );
        }
    }

    private enum TrustMode {
        TYPED,
        UNSTRUCTURED
    }

    private enum NodeReservation {
        RESERVED,
        EXHAUSTED
    }

    private enum TextReservation {
        RESERVED,
        EXHAUSTED
    }

    private enum SequenceStep {
        CONTINUE,
        STOP
    }

    private static final class Budget {

        private int remainingNodes;
        private int remainingStringChars;
        private int remainingEstimatedBytes;

        private Budget(
                int remainingNodes,
                int remainingStringChars,
                int remainingEstimatedBytes
        ) {
            this.remainingNodes =
                    remainingNodes;

            this.remainingStringChars =
                    remainingStringChars;

            this.remainingEstimatedBytes =
                    remainingEstimatedBytes;
        }

        private NodeReservation reserveNode() {
            if (remainingNodes <= 0
                    || remainingEstimatedBytes < 16) {
                return NodeReservation.EXHAUSTED;
            }

            remainingNodes--;
            remainingEstimatedBytes -= 16;

            return NodeReservation.RESERVED;
        }

        private TextReservation reserveText(
                String value
        ) {
            int bytes =
                    utf8LengthStrict(
                            value
                    );

            int chars =
                    value.codePointCount(
                            0,
                            value.length()
                    );

            if (remainingStringChars
                    < chars
                    || remainingEstimatedBytes
                    < bytes) {
                return TextReservation.EXHAUSTED;
            }

            remainingStringChars -=
                    chars;

            remainingEstimatedBytes -=
                    bytes;

            return TextReservation.RESERVED;
        }

        private String consumeString(
                String value
        ) {
            if (remainingStringChars <= 0
                    || remainingEstimatedBytes <= 0) {
                return BUDGET_EXCEEDED_VALUE;
            }

            StringBuilder result =
                    new StringBuilder();

            int usedCodePoints = 0;
            int usedBytes = 0;

            for (int offset = 0;
                 offset < value.length();) {

                int codePoint =
                        value.codePointAt(
                                offset
                        );

                String part =
                        new String(
                                Character.toChars(
                                        codePoint
                                )
                        );

                int bytes =
                        utf8LengthStrict(
                                part
                        );

                if (usedCodePoints + 1
                        > remainingStringChars
                        || usedBytes + bytes
                        > remainingEstimatedBytes) {
                    break;
                }

                result.append(
                        part
                );

                usedCodePoints++;
                usedBytes += bytes;

                offset +=
                        Character.charCount(
                                codePoint
                        );
            }

            if (result.isEmpty()) {
                return BUDGET_EXCEEDED_VALUE;
            }

            remainingStringChars -=
                    usedCodePoints;

            remainingEstimatedBytes -=
                    usedBytes;

            return result.toString();
        }

        private boolean isExhausted() {
            return remainingNodes <= 0
                    || remainingStringChars <= 0
                    || remainingEstimatedBytes <= 0;
        }

        private static int utf8LengthStrict(
                String value
        ) {
            try {
                ByteBuffer bytes =
                        StandardCharsets.UTF_8
                                .newEncoder()
                                .onMalformedInput(
                                        CodingErrorAction.REPORT
                                )
                                .onUnmappableCharacter(
                                        CodingErrorAction.REPORT
                                )
                                .encode(
                                        CharBuffer.wrap(
                                                value
                                        )
                                );

                return bytes.remaining();
            } catch (CharacterCodingException exception) {
                throw new IllegalArgumentException(
                        "Audit details содержит некорректный Unicode",
                        exception
                );
            }
        }
    }
}
