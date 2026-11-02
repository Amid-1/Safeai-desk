package ru.safeai.gateway.knowledge.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import ru.safeai.gateway.knowledge.rag.KnowledgeRagExecutionPermitService;
import ru.safeai.gateway.knowledge.storage.ErasureTombstoneJournal;
import ru.safeai.gateway.knowledge.storage.ObjectStorage;
import ru.safeai.gateway.knowledge.testsupport.KnowledgeErasureTestJournal;

import java.lang.reflect.Constructor;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Проверяет безопасное подключение зависимостей исполнителя удаления документов.
 *
 * <p>Имена Java-методов записаны латиницей, а пояснения оставлены на русском.
 * Тесты не запускают физическое удаление и не подменяют обязательный журнал
 * фиктивным успешным результатом в рабочем коде.</p>
 */
class KnowledgeDocumentDeletionExecutorWiringTest {

    @Test
    void primaryConstructorAcceptsAllFiveDependencies() {
        // Все пять зависимостей должны попасть в соответствующие поля без подмены.
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        KnowledgeRagExecutionPermitService permits = mock(KnowledgeRagExecutionPermitService.class);
        ErasureTombstoneJournal journal = KnowledgeErasureTestJournal.INSTANCE;

        KnowledgeDocumentDeletionExecutor executor = new KnowledgeDocumentDeletionExecutor(
                jdbc, manager, storage, permits, journal);

        assertThat(ReflectionTestUtils.getField(executor, "jdbc")).isSameAs(jdbc);
        assertThat(ReflectionTestUtils.getField(executor, "storage")).isSameAs(storage);
        assertThat(ReflectionTestUtils.getField(executor, "executionPermits")).isSameAs(permits);
        assertThat(ReflectionTestUtils.getField(executor, "tombstoneJournal")).isSameAs(journal);
    }

    @Test
    void springUsesOnlyTheFiveArgumentConstructorForInjection() throws Exception {
        // У Spring должен быть ровно один явно выбранный конструктор внедрения.
        Constructor<KnowledgeDocumentDeletionExecutor> primary =
                KnowledgeDocumentDeletionExecutor.class.getConstructor(
                        JdbcTemplate.class,
                        PlatformTransactionManager.class,
                        ObjectStorage.class,
                        KnowledgeRagExecutionPermitService.class,
                        ErasureTombstoneJournal.class);

        assertThat(primary.isAnnotationPresent(Autowired.class)).isTrue();
        assertThat(Arrays.stream(KnowledgeDocumentDeletionExecutor.class.getDeclaredConstructors())
                .filter(constructor -> constructor.isAnnotationPresent(Autowired.class)))
                .containsExactly(primary);
    }

    @Test
    void legacyConstructorsDoNotInventAnErasureJournal() {
        // Старые конструкторы допускаются только ради совместимости с тестами.
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        KnowledgeRagExecutionPermitService permits = mock(KnowledgeRagExecutionPermitService.class);

        KnowledgeDocumentDeletionExecutor threeArguments =
                new KnowledgeDocumentDeletionExecutor(jdbc, manager, storage);
        KnowledgeDocumentDeletionExecutor fourArguments =
                new KnowledgeDocumentDeletionExecutor(jdbc, manager, storage, permits);

        assertThat(ReflectionTestUtils.getField(threeArguments, "tombstoneJournal")).isNull();
        assertThat(ReflectionTestUtils.getField(fourArguments, "tombstoneJournal")).isNull();
        assertThat(ReflectionTestUtils.getField(fourArguments, "executionPermits")).isSameAs(permits);
    }

    @Test
    void missingErasureJournalFailsClosedForAllLegacyConstructors() {
        // При отсутствии внешнего журнала запрещено начинать необратимое удаление.
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        ObjectStorage storage = mock(ObjectStorage.class);

        KnowledgeDocumentDeletionExecutor threeArguments =
                new KnowledgeDocumentDeletionExecutor(jdbc, manager, storage);
        KnowledgeDocumentDeletionExecutor fourArguments =
                new KnowledgeDocumentDeletionExecutor(jdbc, manager, storage, null);
        KnowledgeDocumentDeletionExecutor fiveArguments =
                new KnowledgeDocumentDeletionExecutor(jdbc, manager, storage, null, null);

        for (KnowledgeDocumentDeletionExecutor executor :
                Arrays.asList(threeArguments, fourArguments, fiveArguments)) {
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                    executor, "requireTombstoneJournal"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("External erasure tombstone journal is required");
        }
    }

    @Test
    void explicitJournalIsRequiredByTheDeletionGuard() {
        // Тестовый журнал передаётся явно: он не должен возникать автоматически.
        KnowledgeDocumentDeletionExecutor executor = new KnowledgeDocumentDeletionExecutor(
                mock(JdbcTemplate.class),
                mock(PlatformTransactionManager.class),
                mock(ObjectStorage.class),
                null,
                KnowledgeErasureTestJournal.INSTANCE);

        // Явная типизация исключает неоднозначный выбор перегрузки AssertJ.assertThat().
        // Проверяем именно тот экземпляр журнала, который был передан в конструктор.
        ErasureTombstoneJournal actualJournal =
                ReflectionTestUtils.invokeMethod(executor, "requireTombstoneJournal");

        assertThat(actualJournal)
                .isSameAs(KnowledgeErasureTestJournal.INSTANCE);
    }

    @Test
    void criticalInfrastructureDependenciesCannotBeNull() {
        // Нельзя создавать исполнителя без БД, управления транзакциями или хранилища.
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        ErasureTombstoneJournal journal = KnowledgeErasureTestJournal.INSTANCE;

        assertThatThrownBy(() -> new KnowledgeDocumentDeletionExecutor(
                null, manager, storage, null, journal))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("jdbc");

        assertThatThrownBy(() -> new KnowledgeDocumentDeletionExecutor(
                jdbc, null, storage, null, journal))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("transactionManager");

        assertThatThrownBy(() -> new KnowledgeDocumentDeletionExecutor(
                jdbc, manager, null, null, journal))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("storage");
    }
}
