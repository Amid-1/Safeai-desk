package ru.safeai.gateway.ai.provider;

import java.io.IOException;

public class AiResponseTooLargeIOException extends IOException {

    public AiResponseTooLargeIOException(long maxBytes) {
        super("AI provider response exceeded " + maxBytes + " bytes");
    }
}
