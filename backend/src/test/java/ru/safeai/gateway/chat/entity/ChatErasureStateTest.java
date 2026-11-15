package ru.safeai.gateway.chat.entity;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.chat.testsupport.ChatTestFixtures;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatErasureStateTest {
    @Test
    void archivedChatCannotBeRenamedUntilRestored() {
        var session = ChatTestFixtures.session();
        var now = Instant.now();
        session.archive(ChatTestFixtures.USER_ID, now);
        assertThatThrownBy(() -> session.renameByUser("Unauthorized", now))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> session.assignAutomaticTitle("Unauthorized", now))
            .isInstanceOf(IllegalStateException.class);
        session.restore(now);
        session.renameByUser("Authorized", now);
        assertThat(session.getTitle()).isEqualTo("Authorized");
        assertThat(session.getErasureState()).isEqualTo(ChatErasureState.AVAILABLE);
    }
}
