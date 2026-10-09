package ru.safeai.gateway.model.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;
import ru.safeai.gateway.model.domain.ModelCatalogEntry;
import ru.safeai.gateway.model.testsupport.ModelTestFixtures;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ModelCatalogRepositoryBindingTest {

    @Test
    void firstPageBindsSqlNullCursorAndExplicitLimit() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        try (PreparedStatement statement = mock(PreparedStatement.class)) {
            doAnswer(invocation -> {
                invocation.<PreparedStatementSetter>getArgument(1).setValues(statement);
                return List.of();
            }).when(jdbc).query(
                    anyString(),
                    any(PreparedStatementSetter.class),
                    org.mockito.ArgumentMatchers.<RowMapper<ModelCatalogEntry>>any()
            );

            assertThat(new ModelCatalogRepository(jdbc)
                    .findVersions("openai:gpt-test", null, 100)).isEmpty();
            verify(statement).setString(1, "openai:gpt-test");
            verify(statement).setNull(2, Types.INTEGER);
            verify(statement).setNull(3, Types.INTEGER);
            verify(statement).setInt(4, 100);
        }
    }

    @Test
    void subsequentPageBindsExclusiveCursorAndLimit() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AtomicReference<String> sql = new AtomicReference<>();
        try (PreparedStatement statement = mock(PreparedStatement.class)) {
            doAnswer(invocation -> {
                sql.set(invocation.getArgument(0));
                invocation.<PreparedStatementSetter>getArgument(1).setValues(statement);
                return List.of();
            }).when(jdbc).query(
                    anyString(),
                    any(PreparedStatementSetter.class),
                    org.mockito.ArgumentMatchers.<RowMapper<ModelCatalogEntry>>any()
            );

            new ModelCatalogRepository(jdbc).findVersions("openai:gpt-test", 9, 25);
            assertThat(sql.get()).contains(
                    "version < ?::integer",
                    "order by version desc limit ?"
            );
            verify(statement).setInt(2, 9);
            verify(statement).setInt(3, 9);
            verify(statement).setInt(4, 25);
        }
    }

    @Test
    void invalidPaginationDoesNotReachTheDatabase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ModelCatalogRepository repository = new ModelCatalogRepository(jdbc);
        assertThatThrownBy(() -> repository.findVersions("key", null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.findVersions("key", null, 201))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.findVersions("key", 0, 100))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void insertBindsAllThirtyThreeParametersIncludingPricingScheme() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Map<Integer, Object> bound = new TreeMap<>();
        AtomicReference<String> sql = new AtomicReference<>();

        try (PreparedStatement statement = mock(PreparedStatement.class, invocation -> {
            if (invocation.getMethod().getName().startsWith("set")) {
                int index = invocation.getArgument(0);
                Object value = invocation.getMethod().getName().equals("setNull")
                        ? null
                        : invocation.getArgument(1);
                bound.put(index, value);
            }
            return null;
        })) {
            doAnswer(invocation -> {
                sql.set(invocation.getArgument(0));
                invocation.<PreparedStatementSetter>getArgument(1).setValues(statement);
                return 1;
            }).when(jdbc).update(anyString(), any(PreparedStatementSetter.class));

            ModelCatalogEntry entry = ModelTestFixtures.freeEntry();
            assertThat(new ModelCatalogRepository(jdbc).insert(entry)).isSameAs(entry);
            assertThat(bound).hasSize(33);
            assertThat(bound.keySet()).containsExactlyElementsOf(
                    java.util.stream.IntStream.rangeClosed(1, 33).boxed().toList());
            assertThat(sql.get().chars().filter(character -> character == '?').count())
                    .isEqualTo(33);
            assertThat(sql.get()).contains("cast(? as jsonb)", "pricing_scheme");
            assertThat(bound)
                    .containsEntry(1, entry.id())
                    .containsEntry(3, entry.version())
                    .containsEntry(19, entry.retentionDays())
                    .containsEntry(23, entry.inputUsdPer1mTokens())
                    .containsEntry(24, entry.cachedInputUsdPer1mTokens())
                    .containsEntry(25, entry.cacheWriteInputUsdPer1mTokens())
                    .containsEntry(26, entry.outputUsdPer1mTokens())
                    .containsEntry(27, entry.extraPricingJson())
                    .containsEntry(28, entry.pricingVersion())
                    .containsEntry(29, Timestamp.from(entry.effectiveFrom()))
                    .containsEntry(30, entry.source().name())
                    .containsEntry(31, entry.createdByUserId())
                    .containsEntry(32, Timestamp.from(entry.createdAt()))
                    .containsEntry(33, "FLAT_TOKEN_V1");
        }
    }
}
