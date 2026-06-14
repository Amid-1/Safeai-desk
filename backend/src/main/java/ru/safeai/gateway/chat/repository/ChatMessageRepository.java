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

    List<ChatMessageEntity> findTop30BySession_IdOrderByCreatedAtDesc(UUID sessionId);

    @Query("""
            select new ru.safeai.gateway.chat.dto.UsageSummaryResponse(
                u.id,
                u.email,
                m.model,
                sum(coalesce(m.inputTokens, 0)),
                sum(coalesce(m.outputTokens, 0)),
                sum(m.costUsd)
            )
            from ChatMessageEntity m
            join m.session s
            join s.user u
            where m.role = ru.safeai.gateway.chat.entity.ChatMessageRole.ASSISTANT
              and m.model is not null
            group by u.id, u.email, m.model
            order by u.email asc, m.model asc
            """)
    List<UsageSummaryResponse> findUsageSummary();

    @Query("""
            select new ru.safeai.gateway.chat.dto.UsageUserSummaryResponse(
                u.id,
                u.email,
                sum(coalesce(m.inputTokens, 0)),
                sum(coalesce(m.outputTokens, 0)),
                sum(m.costUsd)
            )
            from ChatMessageEntity m
            join m.session s
            join s.user u
            where m.role = ru.safeai.gateway.chat.entity.ChatMessageRole.ASSISTANT
              and m.model is not null
            group by u.id, u.email
            order by u.email asc
            """)
    List<UsageUserSummaryResponse> findUsageByUsers();

    @Query("""
            select new ru.safeai.gateway.chat.dto.UsageModelSummaryResponse(
                m.model,
                sum(coalesce(m.inputTokens, 0)),
                sum(coalesce(m.outputTokens, 0)),
                sum(m.costUsd)
            )
            from ChatMessageEntity m
            where m.role = ru.safeai.gateway.chat.entity.ChatMessageRole.ASSISTANT
              and m.model is not null
            group by m.model
            order by m.model asc
            """)
    List<UsageModelSummaryResponse> findUsageByModels();

    @Query("""
            select new ru.safeai.gateway.chat.dto.UsageSummaryResponse(
                u.id,
                u.email,
                m.model,
                sum(coalesce(m.inputTokens, 0)),
                sum(coalesce(m.outputTokens, 0)),
                sum(m.costUsd)
            )
            from ChatMessageEntity m
            join m.session s
            join s.user u
            where m.role = ru.safeai.gateway.chat.entity.ChatMessageRole.ASSISTANT
              and u.id = :userId
              and m.model is not null
            group by u.id, u.email, m.model
            order by m.model asc
            """)
    List<UsageSummaryResponse> findUsageByUserId(@Param("userId") UUID userId);

    @Query("""
            select new ru.safeai.gateway.chat.dto.UsageSummaryResponse(
                u.id,
                u.email,
                m.model,
                sum(coalesce(m.inputTokens, 0)),
                sum(coalesce(m.outputTokens, 0)),
                sum(m.costUsd)
            )
            from ChatMessageEntity m
            join m.session s
            join s.user u
            where m.role = ru.safeai.gateway.chat.entity.ChatMessageRole.ASSISTANT
              and u.organization.id = :organizationId
              and m.model is not null
            group by u.id, u.email, m.model
            order by u.email asc, m.model asc
            """)
    List<UsageSummaryResponse> findUsageByOrganizationId(@Param("organizationId") UUID organizationId);

    @Query(value = """
            select
                date(m.created_at at time zone 'UTC') as "usageDate",
                cast(coalesce(sum(m.input_tokens), 0) as bigint) as "inputTokens",
                cast(coalesce(sum(m.output_tokens), 0) as bigint) as "outputTokens",
                coalesce(sum(m.cost_usd), 0) as "costUsd"
            from chat_messages m
            where m.role = 'ASSISTANT'
              and m.model is not null
            group by date(m.created_at at time zone 'UTC')
            order by date(m.created_at at time zone 'UTC') desc
            """, nativeQuery = true)
    List<UsageDailySummaryProjection> findUsageDaily();
}