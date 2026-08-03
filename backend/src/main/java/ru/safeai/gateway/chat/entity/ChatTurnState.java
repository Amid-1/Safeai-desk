package ru.safeai.gateway.chat.entity;

public enum ChatTurnState {
    NEW,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    AMBIGUOUS
}
