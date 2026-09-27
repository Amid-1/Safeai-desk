// backend/src/main/java/ru/safeai/gateway/chat/title/ChatTitleSource.java
package ru.safeai.gateway.chat.title;

/**
 * Provenance of the visible chat title.
 *
 * <p>DEFAULT may be replaced exactly once by the deterministic automatic
 * title derived from the first accepted USER message. AUTO and USER are
 * stable under normal message traffic. An explicit user rename always moves
 * the title to USER.</p>
 */
public enum ChatTitleSource {
    DEFAULT,
    AUTO,
    USER
}
