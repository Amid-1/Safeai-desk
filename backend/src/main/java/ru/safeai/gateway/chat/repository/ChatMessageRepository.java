package ru.safeai.gateway.chat.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.safeai.gateway.chat.dto.UsageSummaryResponse;
import ru.safeai.gateway.chat.entity.ChatMessageEntity;

import java.util.List;
import java.util.UUID;

public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, UUID> {

    List<ChatMessageEntity> findBySession_IdOrderByCreatedAtAsc(UUID sessionId);

    @Query("""
            select new ru.safeai.gateway.chat.dto.UsageSummaryResponse(
                u.id,
                u.email,
                m.model,
                sum(m.inputTokens),
                sum(m.outputTokens),
                sum(m.inputTokens) + sum(m.outputTokens),
                sum(m.costUsd)
            )
            from ChatMessageEntity m
            join m.session s
            join s.user u
            where m.role = 'ASSISTANT'
              and m.model is not null
              and m.inputTokens is not null
              and m.outputTokens is not null
              and m.costUsd is not null
            group by u.id, u.email, m.model
            order by u.email asc, m.model asc
            """)
    List<UsageSummaryResponse> findUsageSummary();
}