// backend/src/test/java/ru/safeai/gateway/chat/title/ChatTitlePolicyTest.java
package ru.safeai.gateway.chat.title;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatTitlePolicyTest {

    @Test
    void createsReadableRussianTitleFromFirstMessage() {
        String title = ChatTitlePolicy.automaticTitle(
                "Привет!   Нужно подобрать архитектуру авторизации для корпоративного приложения. "
                        + "Нужны refresh rotation и отзыв сессий."
        );

        assertThat(title)
                .isEqualTo("Нужно подобрать архитектуру авторизации для корпоративного приложения.");
    }

    @Test
    void collapsesWhitespaceAndRemovesLeadingMarkdown() {
        String title = ChatTitlePolicy.automaticTitle(
                "##   Как   правильно\n\nнастроить   PostgreSQL для production?"
        );

        assertThat(title)
                .isEqualTo("Как правильно настроить PostgreSQL для production?");
    }

    @Test
    void truncatesOnReadableBoundary() {
        String title = ChatTitlePolicy.automaticTitle(
                "Нужно разработать очень подробный план миграции большого корпоративного сервиса "
                        + "без простоя пользователей и потери данных во время обновления"
        );

        assertThat(title.codePointCount(0, title.length()))
                .isLessThanOrEqualTo(ChatTitlePolicy.MAX_AUTOMATIC_TITLE_CODE_POINTS);
        assertThat(title).endsWith("…");
    }

    @Test
    void manualTitleIsSingleLineAndCannotBeBlank() {
        assertThat(ChatTitlePolicy.normalizeManualTitle("  Мой\n   проект  "))
                .isEqualTo("Мой проект");

        assertThatThrownBy(() -> ChatTitlePolicy.normalizeManualTitle(" \n\t "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
