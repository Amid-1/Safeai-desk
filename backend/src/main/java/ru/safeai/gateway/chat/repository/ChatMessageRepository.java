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
                m.model,
                count(m.id),
                sum(m.inputTokens),
                sum(m.outputTokens),
                sum(m.costUsd)
            )
            from ChatMessageEntity m
            where m.role = 'ASSISTANT'
            group by m.model
            order by sum(m.costUsd) desc
            """)
    List<UsageSummaryResponse> getUsageSummaryByModel();
}