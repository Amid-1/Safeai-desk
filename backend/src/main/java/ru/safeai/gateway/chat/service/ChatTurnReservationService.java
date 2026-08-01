package ru.safeai.gateway.chat.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.chat.dto.SendMessageRequest;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.ratelimit.RedisRateLimitService;

import java.util.Objects;
import java.util.UUID;

/**
 * Короткая транзакционная граница резервирования нового chat turn.
 *
 * <p>Порядок принципиален:</p>
 * <ol>
 *     <li>проверить clientRequestId и вернуть replay без списания лимита;</li>
 *     <li>для нового turn сохранить user message и audit outbox intent;</li>
 *     <li>проверить AI rate limit до commit;</li>
 *     <li>при 429/503 откатить reservation и audit intent;</li>
 *     <li>после commit вызвать AI provider уже без открытой DB transaction.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class ChatTurnReservationService {

    private final ChatPersistenceService
            chatPersistenceService;

    private final RedisRateLimitService
            rateLimitService;

    @Transactional
    public ChatProcessingContext reserveOrReplay(
            UUID chatId,
            SendMessageRequest request,
            SafeAiUserPrincipal currentUser
    ) {
        Objects.requireNonNull(
                chatId,
                "chatId не должен быть null"
        );

        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );

        Objects.requireNonNull(
                currentUser,
                "currentUser не должен быть null"
        );

        ChatProcessingContext context =
                chatPersistenceService
                        .saveUserMessageAndPrepareAiRequest(
                                chatId,
                                request,
                                currentUser
                        );

        /*
         * Existing completed turn не должен повторно расходовать
         * user/organization rate-limit slots.
         */
        if (context.replay()) {
            return context;
        }

        /*
         * Runtime exception от limiter помечает текущую transaction
         * rollback-only. User message и audit outbox intent не останутся
         * в БД после 429 или Redis outage.
         */
        rateLimitService.checkAiMessageAllowed(
                currentUser
        );

        return context;
    }
}
