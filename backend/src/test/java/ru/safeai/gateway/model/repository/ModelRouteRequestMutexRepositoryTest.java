package ru.safeai.gateway.model.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ModelRouteRequestMutexRepositoryTest {

    @Test
    void lockUsesStableNamespacedCompositeIdempotencyKey() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID chatId = UUID.randomUUID();
        UUID clientRequestId = UUID.randomUUID();

        try (Connection connection = mock(Connection.class);
             PreparedStatement statement = mock(PreparedStatement.class)) {
            when(connection.prepareStatement(
                    "select pg_advisory_xact_lock(hashtextextended(?, 0))"
            )).thenReturn(statement);

            doAnswer(invocation -> {
                ConnectionCallback<?> callback = invocation.getArgument(0);
                return callback.doInConnection(connection);
            }).when(jdbc).execute(
                    org.mockito.ArgumentMatchers.<ConnectionCallback<Object>>any()
            );

            new ModelRouteRequestMutexRepository(jdbc)
                    .lock(chatId, clientRequestId);

            verify(statement).setString(
                    1,
                    "safeai:model-route:" + chatId + ":" + clientRequestId
            );
            verify(statement).execute();
            verify(statement).close();
        }
    }

    @Test
    void lockRequiresAnExistingTransactionByContract() throws Exception {
        Method method = ModelRouteRequestMutexRepository.class.getMethod(
                "lock", UUID.class, UUID.class
        );
        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
    }

    @Test
    void nullLockIdentityFailsBeforeJdbcInteraction() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ModelRouteRequestMutexRepository repository = new ModelRouteRequestMutexRepository(jdbc);

        assertThatThrownBy(() -> repository.lock(null, UUID.randomUUID()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("chatId");
        assertThatThrownBy(() -> repository.lock(UUID.randomUUID(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("clientRequestId");

        verifyNoInteractions(jdbc);
    }
}
