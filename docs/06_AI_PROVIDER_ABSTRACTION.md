# SafeAI Desk — AI Provider abstraction

Документ описывает слой AI Provider.

## 1. Зачем нужен

`ChatService` не должен знать, какой AI-провайдер используется.

Он должен зависеть от интерфейса:

```java
private final AiProvider aiProvider;
```

Тогда mock можно заменить на реальный provider без переписывания chat-логики.

## 2. Структура

```text
ru.safeai.gateway.ai
├── AiProvider.java
├── AiChatRequest.java
├── AiChatResponse.java
├── AiMessage.java
└── MockAiProvider.java
```

## 3. Схема

```text
ChatController
    ↓
ChatService
    ↓
AiProvider interface
    ↓
MockAiProvider
    ↓
AiChatResponse
    ↓
chat_messages
```

## 4. AiProvider

```java
package ru.safeai.gateway.ai;

public interface AiProvider {
    AiChatResponse sendMessage(AiChatRequest request);
}
```

## 5. AiMessage

```java
package ru.safeai.gateway.ai;

public record AiMessage(
        String role,
        String content
) {
}
```

## 6. AiChatRequest

```java
package ru.safeai.gateway.ai;

import java.util.List;
import java.util.UUID;

public record AiChatRequest(
        UUID userId,
        UUID organizationId,
        UUID chatId,
        String userMessage,
        List<AiMessage> history
) {
}
```

## 7. AiChatResponse

```java
package ru.safeai.gateway.ai;

import java.math.BigDecimal;

public record AiChatResponse(
        String content,
        String model,
        Integer inputTokens,
        Integer outputTokens,
        BigDecimal costUsd
) {
}
```

## 8. MockAiProvider

```java
package ru.safeai.gateway.ai;

import org.springframework.stereotype.Service;
import java.math.BigDecimal;

@Service
public class MockAiProvider implements AiProvider {

    private static final String MOCK_MODEL = "mock-safeai";

    @Override
    public AiChatResponse sendMessage(AiChatRequest request) {
        String content = "Mock AI provider response: " + request.userMessage();

        return new AiChatResponse(
                content,
                MOCK_MODEL,
                Math.max(1, request.userMessage().length() / 4),
                Math.max(1, content.length() / 4),
                BigDecimal.ZERO
        );
    }
}
```

## 9. Проверка

После отправки сообщения у `ASSISTANT` должно быть:

```text
content starts with Mock AI provider response
model = mock-safeai
inputTokens != null
outputTokens != null
costUsd = 0
```

SQL:

```bat
docker exec safeai-postgres psql -U safeai -d safeai -c "select role, content, model, input_tokens, output_tokens, cost_usd from chat_messages order by created_at desc limit 10;"
```

## 10. Успешный результат

```text
✅ есть пакет ru.safeai.gateway.ai
✅ ChatService зависит от AiProvider
✅ MockAiProvider помечен @Service
✅ ответ приходит через aiProvider.sendMessage(...)
✅ model/tokens/cost сохраняются в chat_messages
```
