package ru.safeai.gateway.chat.entity;

/** Lifecycle state of chat CONTENT (immutable accounting remains). */
public enum ChatErasureState {
    AVAILABLE, ERASING, ERASED
}
