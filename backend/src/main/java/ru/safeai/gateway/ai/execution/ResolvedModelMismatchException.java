package ru.safeai.gateway.ai.execution;

public final class ResolvedModelMismatchException extends RuntimeException {
    public ResolvedModelMismatchException(String requested, String actual) {
        super("Provider resolved model is not approved: requested=" + requested + ", actual=" + actual);
    }
}
