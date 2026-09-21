package ru.safeai.gateway.knowledge.rag;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.chat.service.ChatProcessingContext;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static ru.safeai.gateway.knowledge.testsupport.KnowledgeRegressionFixtures.*;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class AnswerPassportServiceBoundaryTest {
    @Mock JdbcTemplate jdbc;
    @Mock AuditEventService audit;

    @Test
    void generalModeNeverWritesPassportOrAudit() {
        var service = new AnswerPassportService(jdbc, audit);
        var prepared = RagPreparation.general(request(null, null));
        var completion = RagCompletion.general(prepared, response("Answer"));
        assertThat(service.persist(context(null, KnowledgeMode.GENERAL), UUID.randomUUID(),
                "openai", completion, principal(), NOW)).isNull();
        verifyNoInteractions(jdbc, audit);
    }

    @Test
    void invalidCitationSetCannotBePersistedEvenWithKnownSources() {
        var prepared = preparation(KnowledgeMode.KNOWLEDGE_ONLY, 1);
        var impossible = new RagCompletion(prepared, response("Fact [C1]"),
                List.of(new RagCitation("C1", 1, prepared.sources().getFirst().hit().chunkId())),
                false, false);
        var service = new AnswerPassportService(jdbc, audit);
        assertThatThrownBy(() -> service.persist(context(UUID.randomUUID(),
                KnowledgeMode.KNOWLEDGE_ONLY), UUID.randomUUID(), "openai", impossible,
                principal(), NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid citation set");
        verifyNoInteractions(jdbc, audit);
    }

    @Test
    void knowledgePassportMustCarryPersistedRouteDecisionIdentity() {
        var prepared = preparation(KnowledgeMode.KNOWLEDGE_ONLY, 1);
        var completion = new KnowledgeCitationValidator().validate(prepared, response("Fact [C1]"));
        assertThatThrownBy(() -> new AnswerPassportService(jdbc, audit)
                .persist(context(null, KnowledgeMode.KNOWLEDGE_ONLY), UUID.randomUUID(),
                        "openai", completion, principal(), NOW))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("modelRouteDecisionId");
        verifyNoInteractions(jdbc, audit);
    }

    @Test
    void durablePassportBoundaryMustParticipateInCallingChatTransaction() throws Exception {
        Method method = AnswerPassportService.class.getMethod("persist",
                ChatProcessingContext.class, UUID.class, String.class, RagCompletion.class,
                SafeAiUserPrincipal.class, java.time.Instant.class);
        Transactional transactional = method.getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
    }

    private static SafeAiUserPrincipal principal() {
        return SafeAiUserPrincipal.accessTokenPrincipal(USER_ID, ORG_ID, 0L, 0L,
                Set.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static ChatProcessingContext context(UUID decisionId, KnowledgeMode mode) {
        return new ChatProcessingContext(CHAT_ID, TURN_ID, UUID.randomUUID(), UUID.randomUUID(),
                OP_ID, UUID.randomUUID(), NOW.plusSeconds(90), decisionId,
                "model", request(4_096L, 100), mode.usesKnowledge() ? KB_ID : null, mode, false);
    }
}
