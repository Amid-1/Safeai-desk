package ru.safeai.gateway.chat.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.safeai.gateway.chat.dto.UsageModelSummaryResponse;
import ru.safeai.gateway.chat.dto.UsageSummaryResponse;
import ru.safeai.gateway.chat.dto.UsageUserSummaryResponse;
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

    @Query("""
            select new ru.safeai.gateway.chat.dto.UsageUserSummaryResponse(
                u.id,
                u.email,
                sum(m.inputTokens),
                sum(m.outputTokens),
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
            group by u.id, u.email
            order by u.email asc
            """)
    List<UsageUserSummaryResponse> findUsageByUsers();

    @Query("""
            select new ru.safeai.gateway.chat.dto.UsageModelSummaryResponse(
                m.model,
                sum(m.inputTokens),
                sum(m.outputTokens),
                sum(m.costUsd)
            )
            from ChatMessageEntity m
            where m.role = 'ASSISTANT'
              and m.model is not null
              and m.inputTokens is not null
              and m.outputTokens is not null
              and m.costUsd is not null
            group by m.model
            order by m.model asc
            """)
    List<UsageModelSummaryResponse> findUsageByModels();

    @Query("""
            select new ru.safeai.gateway.chat.dto.UsageSummaryResponse(
                u.id,
                u.email,
                m.model,
                sum(m.inputTokens),
                sum(m.outputTokens),
                sum(m.costUsd)
            )
            from ChatMessageEntity m
            join m.session s
            join s.user u
            where m.role = 'ASSISTANT'
              and u.id = :userId
              and m.model is not null
              and m.inputTokens is not null
              and m.outputTokens is not null
              and m.costUsd is not null
            group by u.id, u.email, m.model
            order by m.model asc
            """)
    List<UsageSummaryResponse> findUsageByUserId(@Param("userId") UUID userId);

    @Query("""
            select new ru.safeai.gateway.chat.dto.UsageSummaryResponse(
                u.id,
                u.email,
                m.model,
                sum(m.inputTokens),
                sum(m.outputTokens),
                sum(m.costUsd)
            )
            from ChatMessageEntity m
            join m.session s
            join s.user u
            where m.role = 'ASSISTANT'
              and u.organization.id = :organizationId
              and m.model is not null
              and m.inputTokens is not null
              and m.outputTokens is not null
              and m.costUsd is not null
            group by u.id, u.email, m.model
            order by u.email asc, m.model asc
            """)
    List<UsageSummaryResponse> findUsageByOrganizationId(@Param("organizationId") UUID organizationId);

    @Query(value = """
            select
                cast(m.created_at as date) as "usageDate",
                cast(sum(m.input_tokens) as bigint) as "inputTokens",
                cast(sum(m.output_tokens) as bigint) as "outputTokens",
                sum(m.cost_usd) as "costUsd"
            from chat_messages m
            where m.role = 'ASSISTANT'
              and m.model is not null
              and m.input_tokens is not null
              and m.output_tokens is not null
              and m.cost_usd is not null
            group by cast(m.created_at as date)
            order by cast(m.created_at as date) desc
            """, nativeQuery = true)
    List<UsageDailySummaryProjection> findUsageDaily();
}