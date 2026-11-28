package ru.safeai.gateway.erasure;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.erasure.ErasureCaseTypes.Operation;
import ru.safeai.gateway.erasure.ErasureCaseTypes.ResourceType;
import ru.safeai.gateway.erasure.ErasureCaseTypes.Target;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ErasureCaseRulesTest {

    private static final UUID RESOURCE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");

    /**
     * Explicit R1a security allowlist. Adding a new type, operation or scope
     * requires a deliberate review and an update of this test.
     */
    private static final List<AllowedTarget> ALLOWED = List.of(
            new AllowedTarget(ResourceType.CHAT, Operation.REDACT_CONTENT,
                    "FULL_CHAT_CONTENT"),
            new AllowedTarget(ResourceType.USER_ACCOUNT, Operation.DELETE_ACCOUNT,
                    "PERSONAL_DATA_AND_ACCESS"),
            new AllowedTarget(ResourceType.KNOWLEDGE_DOCUMENT, Operation.DELETE_OBJECT,
                    "FULL_DOCUMENT"),
            new AllowedTarget(ResourceType.KNOWLEDGE_BASE, Operation.DELETE_OBJECT,
                    "FULL_BASE"),
            new AllowedTarget(ResourceType.ORGANIZATION, Operation.OFFBOARD_TENANT,
                    "TENANT_CLOSURE")
    );

    private record AllowedTarget(ResourceType resourceType, Operation operation,
                                 String scope) { }

    @Test
    void knownResourceTypesAndOperationsRequireExplicitReview() {
        assertThat(ResourceType.values()).containsExactlyInAnyOrder(
                ResourceType.CHAT,
                ResourceType.USER_ACCOUNT,
                ResourceType.KNOWLEDGE_DOCUMENT,
                ResourceType.KNOWLEDGE_BASE,
                ResourceType.ORGANIZATION
        );
        assertThat(Operation.values()).containsExactlyInAnyOrder(
                Operation.REDACT_CONTENT,
                Operation.DELETE_ACCOUNT,
                Operation.DELETE_OBJECT,
                Operation.OFFBOARD_TENANT
        );
    }

    @Test
    void acceptsOnlyExplicitlyApprovedResourceOperationScopeTriples() {
        String[] scopes = {
                "FULL_CHAT_CONTENT",
                "PERSONAL_DATA_AND_ACCESS",
                "FULL_DOCUMENT",
                "FULL_BASE",
                "TENANT_CLOSURE",
                "DELETE_ALL",
                "full_chat_content",
                " FULL_CHAT_CONTENT",
                "FULL_CHAT_CONTENT ",
                "",
                null
        };

        for (ResourceType resourceType : ResourceType.values()) {
            for (Operation operation : Operation.values()) {
                for (String scope : scopes) {
                    Target target = new Target(resourceType, RESOURCE_ID,
                            operation, scope);
                    boolean allowed = ALLOWED.stream().anyMatch(rule ->
                            rule.resourceType() == resourceType
                                    && rule.operation() == operation
                                    && rule.scope().equals(scope));

                    if (allowed) {
                        assertThatCode(() -> ErasureCaseRules.validate(target))
                                .as("Allowed: %s / %s / %s",
                                        resourceType, operation, scope)
                                .doesNotThrowAnyException();
                    } else {
                        assertThatThrownBy(() -> ErasureCaseRules.validate(target))
                                .as("Rejected: %s / %s / %s",
                                        resourceType, operation, scope)
                                .isInstanceOf(BadRequestException.class);
                    }
                }
            }
        }
    }

    @Test
    void rejectsNullTargetAndMissingIdentityOrEnumFields() {
        assertInvalidTarget(null);
        assertInvalidTarget(new Target(
                null, RESOURCE_ID, Operation.REDACT_CONTENT, "FULL_CHAT_CONTENT"));
        assertInvalidTarget(new Target(
                ResourceType.CHAT, null, Operation.REDACT_CONTENT, "FULL_CHAT_CONTENT"));
        assertInvalidTarget(new Target(
                ResourceType.CHAT, RESOURCE_ID, null, "FULL_CHAT_CONTENT"));
    }

    /**
     * Negative tests intentionally pass invalid values. Routing them through
     * a shared assertion avoids IDE false positives for literal null arguments
     * while retaining the actual BadRequestException contract checks.
     */
    private static void assertInvalidTarget(Target target) {
        assertThatThrownBy(() -> ErasureCaseRules.validate(target))
                .as("Invalid erasure target must be rejected: %s", target)
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void acceptsOnlyValidIdempotencyKeyBoundaries() {
        for (String valid : new String[]{
                "a".repeat(12),
                "a".repeat(128),
                "AbCdEf012345._:-",
                "chat-1234567890"
        }) {
            assertThatCode(() -> ErasureCaseRules.key(valid))
                    .as("Valid key length %s", valid.length())
                    .doesNotThrowAnyException();
        }

        for (String invalid : new String[]{
                null,
                "",
                "a".repeat(11),
                "a".repeat(129),
                " spaces bad ",
                "abc0123456789 ",
                "abc0123456789\n",
                "abc0123456789/",
                "abc0123456789?",
                "привет456789012",
                "abc01234567é"
        }) {
            assertThatThrownBy(() -> ErasureCaseRules.key(invalid))
                    .as("Invalid idempotency key")
                    .isInstanceOf(BadRequestException.class);
        }
    }

    @Test
    void digestRequiresExactly64LowercaseHexCharacters() {
        for (String valid : new String[]{
                "a".repeat(64),
                "0".repeat(64),
                "0123456789abcdef".repeat(4)
        }) {
            assertThatCode(() -> ErasureCaseRules.digest(valid))
                    .doesNotThrowAnyException();
        }

        for (String invalid : new String[]{
                null,
                "",
                "a".repeat(63),
                "a".repeat(65),
                "A".repeat(64),
                "z".repeat(64),
                "a".repeat(63) + " ",
                "a".repeat(63) + "\n"
        }) {
            assertThatThrownBy(() -> ErasureCaseRules.digest(invalid))
                    .isInstanceOf(BadRequestException.class);
        }
    }

    @Test
    void reasonsAreStrictAllowlistedCodesOnly() {
        for (String valid : new String[]{
                "SELF_REQUEST",
                "OWNER_REQUEST",
                "ADMINISTRATIVE_REQUEST",
                "ORGANIZATION_REQUEST"
        }) {
            assertThatCode(() -> ErasureCaseRules.reason(valid))
                    .doesNotThrowAnyException();
        }

        for (String invalid : new String[]{
                null,
                "",
                "SELF_REQUEST ",
                "self_request",
                "DELETE_ALL",
                "Delete because personal data",
                "SELF_REQUEST\n",
                "ORGANIZATION_REQUEST_EXTRA"
        }) {
            assertThatThrownBy(() -> ErasureCaseRules.reason(invalid))
                    .isInstanceOf(BadRequestException.class);
        }
    }

    @Test
    void sha256UsesStableUtf8EncodingAndIsSensitiveToChanges() {
        assertThat(ErasureCaseDigest.sha("abc")).isEqualTo(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(ErasureCaseDigest.sha("Привет")).isEqualTo(
                "dd679c0b9fd408a04148aa7d30c9df393f67b7227f65693fffe0ed6d0f0ade59");

        String hash = ErasureCaseDigest.sha("case-no-pii");
        assertThat(hash).hasSize(64).matches("[a-f0-9]{64}");
        assertThat(ErasureCaseDigest.sha("case-no-pii")).isEqualTo(hash);
        assertThat(ErasureCaseDigest.sha("case-no-pii ")).isNotEqualTo(hash);
        assertThat(ErasureCaseDigest.sha("changed")).isNotEqualTo(hash);
    }
}
