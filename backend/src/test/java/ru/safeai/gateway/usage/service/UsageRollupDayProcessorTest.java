package ru.safeai.gateway.usage.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.safeai.gateway.usage.config.UsageJdbcClients;
import ru.safeai.gateway.usage.repository.UsageRollupStateRepository;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UsageRollupDayProcessorTest {

    private static final LocalDate DATE =
            LocalDate.of(2026, 6, 10);

    @Mock
    private UsageJdbcClients jdbcClients;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private UsageRollupStateRepository stateRepository;

    private UsageRollupDayProcessor processor;

    @BeforeEach
    void setUp() {
        when(jdbcClients.rollup())
                .thenReturn(jdbcTemplate);

        processor = new UsageRollupDayProcessor(
                jdbcClients,
                stateRepository
        );
    }

    @Test
    @SuppressWarnings("SqlSourceToSinkFlow")
    void watermarkAdvancesOnlyAfterAllRollupWritesSucceed() {
        when(jdbcTemplate.update(
                anyString(),
                any(Object[].class)
        )).thenReturn(1);

        processor.rebuildDay(
                DATE,
                true
        );

        InOrder order = inOrder(
                jdbcTemplate,
                stateRepository
        );

        order.verify(jdbcTemplate).update(
                argThat(sql ->
                        sql.contains(
                                "delete from "
                                        + "usage_daily_user_model_rollups"
                        )
                ),
                any(Object[].class)
        );

        order.verify(jdbcTemplate).update(
                argThat(sql ->
                        sql.contains(
                                "delete from "
                                        + "usage_daily_org_model_rollups"
                        )
                ),
                any(Object[].class)
        );

        order.verify(jdbcTemplate).update(
                argThat(sql ->
                        sql.contains(
                                "delete from "
                                        + "usage_daily_quality_rollups"
                        )
                ),
                any(Object[].class)
        );

        order.verify(jdbcTemplate).update(
                argThat(sql ->
                        sql.contains(
                                "insert into "
                                        + "usage_daily_user_model_rollups"
                        )
                ),
                any(Object[].class)
        );

        order.verify(jdbcTemplate).update(
                argThat(sql ->
                        sql.contains(
                                "insert into "
                                        + "usage_daily_org_model_rollups"
                        )
                ),
                any(Object[].class)
        );

        /*
         * В processor есть две записи в
         * usage_daily_quality_rollups:
         *
         * 1. агрегаты качества из chat_messages;
         * 2. upsert количества AMBIGUOUS turn из chat_turns.
         *
         * Поэтому одного contains(tableName) недостаточно:
         * такой matcher совпадает с обоими вызовами.
         */
        order.verify(jdbcTemplate).update(
                argThat(sql ->
                        sql.contains(
                                "insert into "
                                        + "usage_daily_quality_rollups"
                        )
                                && sql.contains(
                                "assistant_message_count"
                        )
                                && !sql.contains(
                                "ambiguous_provider_operation_count"
                        )
                ),
                any(Object[].class)
        );

        order.verify(jdbcTemplate).update(
                argThat(sql ->
                        sql.contains(
                                "insert into "
                                        + "usage_daily_quality_rollups"
                        )
                                && sql.contains(
                                "ambiguous_provider_operation_count"
                        )
                                && sql.contains(
                                "from chat_turns"
                        )
                ),
                any(Object[].class)
        );

        order.verify(stateRepository)
                .markCompleted(DATE);
    }

    @Test
    void failedDayNeverAdvancesWatermark() {
        when(jdbcTemplate.update(
                anyString(),
                any(Object[].class)
        )).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);

            if (sql.contains(
                    "insert into "
                            + "usage_daily_user_model_rollups"
            )) {
                throw new DataIntegrityViolationException(
                        "simulated rollup failure"
                );
            }

            return 1;
        });

        assertThatThrownBy(() ->
                processor.rebuildDay(
                        DATE,
                        true
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                )
                .hasMessageContaining("simulated");

        verify(
                stateRepository,
                never()
        ).markCompleted(any());
    }

    @Test
    void lookbackRebuildDoesNotAdvanceContiguousWatermark() {
        when(jdbcTemplate.update(
                anyString(),
                any(Object[].class)
        )).thenReturn(1);

        processor.rebuildDay(
                DATE,
                false
        );

        verify(
                stateRepository,
                never()
        ).markCompleted(any());
    }
}