// backend/src/test/java/ru/safeai/gateway/knowledge/rag/KnowledgeRagExpiredLeaseRecoveryPolicyTest.java
package ru.safeai.gateway.knowledge.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import ru.safeai.gateway.ai.execution.ExecutionAttemptOutcome;
import ru.safeai.gateway.ai.execution.OutcomeCertainty;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Регрессионная проверка строгой матрицы восстановления просроченных RAG-аренд.
 *
 * <p>Сценарии перебирают все известные значения outcome/certainty из Java-модели
 * и обе возможные ситуации с finished_at. Только четыре совместимые пары
 * с подтверждённым временем завершения могут освободить аренду.</p>
 *
 * <p>Параметры поступают через JUnit, поэтому тест проверяет реальный результат
 * метода, а не подменяет его константным выражением, свернутым инспекцией IDE.</p>
 */
class KnowledgeRagExpiredLeaseRecoveryPolicyTest {

    /**
     * Независимое описание разрешённых доказательств из V66.
     * Ключи составлены из фактических строк, сохраняемых в PostgreSQL.
     */
    private static final Map<String, String> CONFIRMED_OUTCOMES = Map.of(
            "SUCCEEDED|KNOWN_EXECUTED", "SUCCEEDED",
            "PROTOCOL_REJECTED|KNOWN_EXECUTED", "FAILED",
            "FAILED|KNOWN_NOT_EXECUTED", "FAILED",
            "FAILED|KNOWN_REJECTED", "FAILED"
    );

    @ParameterizedTest(name = "{index}: исход={0}, достоверность={1}, finished_at={2}")
    @MethodSource("allRecognizedEvidence")
    void verifiesEveryKnownOutcomeCertaintyAndFinishCombination(
            String outcome,
            String certainty,
            boolean finished,
            String expected
    ) {
        String actual = KnowledgeRagExecutionPermitService.verifiedTerminalOutcome(
                outcome, certainty, finished);

        assertThat(actual)
                .as("Несовместимое доказательство не должно освобождать аренду: %s / %s, finished=%s",
                        outcome, certainty, finished)
                .isEqualTo(expected);
    }

    /**
     * Четыре подтверждённых сочетания должны действительно освобождать аренду,
     * а не возвращать null, как может ошибочно утверждать инспекция IntelliJ.
     */
    @ParameterizedTest(name = "{index}: подтверждено {0} / {1}")
    @MethodSource("confirmedEvidence")
    void confirmedTerminalEvidenceMustReturnNonNullOutcome(
            String outcome, String certainty, String expected
    ) {
        String actual = KnowledgeRagExecutionPermitService.verifiedTerminalOutcome(
                outcome, certainty, true);

        assertThat(actual)
                .as("Доказательство завершения должно иметь конкретный терминальный исход")
                .isNotNull()
                .isEqualTo(expected);
    }

    @ParameterizedTest(name = "{index}: повреждено outcome={0}, certainty={1}, finished={2}")
    @MethodSource("unknownAndMalformedEvidence")
    void unknownOrMalformedEvidenceNeverUnblocksErasure(
            String outcome, String certainty, boolean finished
    ) {
        String actual = KnowledgeRagExecutionPermitService.verifiedTerminalOutcome(
                outcome, certainty, finished);

        assertThat(actual)
                .as("Неизвестное доказательство не может снять блокировку удаления")
                .isNull();
    }

    @Test
    void enumMatrixContainsExactlyFourConfirmedPairs() {
        // При расширении enum тест должен обнаружить любое несовпадение
        // между миграционной политикой и политикой восстановления.
        assertThat(CONFIRMED_OUTCOMES).hasSize(4);
        assertThat(ExecutionAttemptOutcome.values()).hasSize(5);
        assertThat(OutcomeCertainty.values()).hasSize(4);
    }

    private static Stream<Arguments> allRecognizedEvidence() {
        return Arrays.stream(ExecutionAttemptOutcome.values())
                .flatMap(outcome -> Arrays.stream(OutcomeCertainty.values())
                        .flatMap(certainty -> Stream.of(true, false)
                                .map(finished -> Arguments.of(
                                        outcome.name(),
                                        certainty.name(),
                                        finished,
                                        finished ? CONFIRMED_OUTCOMES.get(
                                                outcome.name() + "|" + certainty.name()) : null
                                ))));
    }

    private static Stream<Arguments> confirmedEvidence() {
        return CONFIRMED_OUTCOMES.entrySet().stream().map(entry -> {
            String[] parts = entry.getKey().split("\\|", -1);
            return Arguments.of(parts[0], parts[1], entry.getValue());
        });
    }

    private static Stream<Arguments> unknownAndMalformedEvidence() {
        return Stream.of(
                Arguments.of(null, "KNOWN_EXECUTED", true),
                Arguments.of("SUCCEEDED", null, true),
                Arguments.of(null, null, true),
                Arguments.of("SUCCEEDED", "FUTURE_CERTAINTY", true),
                Arguments.of("FUTURE_OUTCOME", "KNOWN_EXECUTED", true),
                Arguments.of("", "KNOWN_EXECUTED", true),
                Arguments.of("SUCCEEDED", "", true),
                Arguments.of("SUCCEEDED ", "KNOWN_EXECUTED", true),
                Arguments.of("SUCCEEDED", "KNOWN_EXECUTED ", true),
                Arguments.of("succeeded", "KNOWN_EXECUTED", true),
                Arguments.of("FAILED", "known_rejected", true),
                Arguments.of("SUCCEEDED", "KNOWN_EXECUTED", false),
                Arguments.of("FAILED", "KNOWN_REJECTED", false),
                Arguments.of("PROTOCOL_REJECTED", "KNOWN_EXECUTED", false)
        );
    }
}
