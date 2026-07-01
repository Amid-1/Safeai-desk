package ru.safeai.gateway.usage.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.safeai.gateway.chat.entity.ChatMessageEntity;
import ru.safeai.gateway.usage.dto.UsageModelSummaryResponse;
import ru.safeai.gateway.usage.dto.UsageSummaryResponse;
import ru.safeai.gateway.usage.dto.UsageUserSummaryResponse;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface UsageQueryRepository extends JpaRepository<ChatMessageEntity, UUID> {

    @Query("""
            select new ru.safeai.gateway.usage.dto.UsageSummaryResponse(
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
              and m.status = ru.safeai.gateway.chat.entity.ChatMessageStatus.COMPLETED
              and m.model is not null
              and m.createdAt >= :dateFrom
              and m.createdAt < :dateTo
              and (:model is null or m.model = :model)
            group by u.id, u.email, m.model
            order by u.email asc, m.model asc
            """)
    List<UsageSummaryResponse> findUsageSummary(
            @Param("dateFrom") Instant dateFrom,
            @Param("dateTo") Instant dateTo,
            @Param("model") String model
    );

    @Query("""
            select new ru.safeai.gateway.usage.dto.UsageUserSummaryResponse(
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
              and m.status = ru.safeai.gateway.chat.entity.ChatMessageStatus.COMPLETED
              and m.model is not null
              and m.createdAt >= :dateFrom
              and m.createdAt < :dateTo
            group by u.id, u.email
            order by u.email asc
            """)
    List<UsageUserSummaryResponse> findUsageByUsers(
            @Param("dateFrom") Instant dateFrom,
            @Param("dateTo") Instant dateTo
    );

    @Query("""
            select new ru.safeai.gateway.usage.dto.UsageModelSummaryResponse(
                m.model,
                sum(coalesce(m.inputTokens, 0)),
                sum(coalesce(m.outputTokens, 0)),
                sum(m.costUsd)
            )
            from ChatMessageEntity m
            where m.role = ru.safeai.gateway.chat.entity.ChatMessageRole.ASSISTANT
              and m.status = ru.safeai.gateway.chat.entity.ChatMessageStatus.COMPLETED
              and m.model is not null
              and m.createdAt >= :dateFrom
              and m.createdAt < :dateTo
            group by m.model
            order by m.model asc
            """)
    List<UsageModelSummaryResponse> findUsageByModels(
            @Param("dateFrom") Instant dateFrom,
            @Param("dateTo") Instant dateTo
    );

    @Query("""
            select new ru.safeai.gateway.usage.dto.UsageSummaryResponse(
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
              and m.status = ru.safeai.gateway.chat.entity.ChatMessageStatus.COMPLETED
              and u.id = :userId
              and m.model is not null
              and m.createdAt >= :dateFrom
              and m.createdAt < :dateTo
              and (:model is null or m.model = :model)
            group by u.id, u.email, m.model
            order by m.model asc
            """)
    List<UsageSummaryResponse> findUsageByUserId(
            @Param("userId") UUID userId,
            @Param("dateFrom") Instant dateFrom,
            @Param("dateTo") Instant dateTo,
            @Param("model") String model
    );

    @Query("""
            select new ru.safeai.gateway.usage.dto.UsageSummaryResponse(
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
              and m.status = ru.safeai.gateway.chat.entity.ChatMessageStatus.COMPLETED
              and m.organization.id = :organizationId
              and m.model is not null
              and m.createdAt >= :dateFrom
              and m.createdAt < :dateTo
              and (:model is null or m.model = :model)
            group by u.id, u.email, m.model
            order by u.email asc, m.model asc
            """)
    List<UsageSummaryResponse> findUsageByOrganizationId(
            @Param("organizationId") UUID organizationId,
            @Param("dateFrom") Instant dateFrom,
            @Param("dateTo") Instant dateTo,
            @Param("model") String model
    );

    @Query(value = """
            select
                date(m.created_at at time zone 'UTC') as "usageDate",
                cast(coalesce(sum(m.input_tokens), 0) as bigint) as "inputTokens",
                cast(coalesce(sum(m.output_tokens), 0) as bigint) as "outputTokens",
                coalesce(sum(m.cost_usd), 0) as "costUsd"
            from chat_messages m
            where m.role = 'ASSISTANT'
              and m.status = 'COMPLETED'
              and m.model is not null
              and m.created_at >= :dateFrom
              and m.created_at < :dateTo
            group by date(m.created_at at time zone 'UTC')
            order by date(m.created_at at time zone 'UTC') desc
            """, nativeQuery = true)
    List<UsageDailySummaryProjection> findUsageDaily(
            @Param("dateFrom") Instant dateFrom,
            @Param("dateTo") Instant dateTo
    );

    @Query("""
            select new ru.safeai.gateway.usage.dto.UsageUserSummaryResponse(
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
              and m.status = ru.safeai.gateway.chat.entity.ChatMessageStatus.COMPLETED
              and m.organization.id = :organizationId
              and m.model is not null
              and m.createdAt >= :dateFrom
              and m.createdAt < :dateTo
            group by u.id, u.email
            order by u.email asc
            """)
    List<UsageUserSummaryResponse> findUsageByUsersByOrganizationId(
            @Param("organizationId") UUID organizationId,
            @Param("dateFrom") Instant dateFrom,
            @Param("dateTo") Instant dateTo
    );

    @Query("""
            select new ru.safeai.gateway.usage.dto.UsageModelSummaryResponse(
                m.model,
                sum(coalesce(m.inputTokens, 0)),
                sum(coalesce(m.outputTokens, 0)),
                sum(m.costUsd)
            )
            from ChatMessageEntity m
            where m.role = ru.safeai.gateway.chat.entity.ChatMessageRole.ASSISTANT
              and m.status = ru.safeai.gateway.chat.entity.ChatMessageStatus.COMPLETED
              and m.organization.id = :organizationId
              and m.model is not null
              and m.createdAt >= :dateFrom
              and m.createdAt < :dateTo
            group by m.model
            order by m.model asc
            """)
    List<UsageModelSummaryResponse> findUsageByModelsByOrganizationId(
            @Param("organizationId") UUID organizationId,
            @Param("dateFrom") Instant dateFrom,
            @Param("dateTo") Instant dateTo
    );

    @Query(value = """
            select
                date(m.created_at at time zone 'UTC') as "usageDate",
                cast(coalesce(sum(m.input_tokens), 0) as bigint) as "inputTokens",
                cast(coalesce(sum(m.output_tokens), 0) as bigint) as "outputTokens",
                coalesce(sum(m.cost_usd), 0) as "costUsd"
            from chat_messages m
            where m.role = 'ASSISTANT'
              and m.status = 'COMPLETED'
              and m.model is not null
              and m.organization_id = :organizationId
              and m.created_at >= :dateFrom
              and m.created_at < :dateTo
            group by date(m.created_at at time zone 'UTC')
            order by date(m.created_at at time zone 'UTC') desc
            """, nativeQuery = true)
    List<UsageDailySummaryProjection> findUsageDailyByOrganizationId(
            @Param("organizationId") UUID organizationId,
            @Param("dateFrom") Instant dateFrom,
            @Param("dateTo") Instant dateTo
    );

    @Query("""
            select new ru.safeai.gateway.usage.dto.UsageSummaryResponse(
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
              and m.status = ru.safeai.gateway.chat.entity.ChatMessageStatus.COMPLETED
              and u.id = :userId
              and m.organization.id = :organizationId
              and m.model is not null
              and m.createdAt >= :dateFrom
              and m.createdAt < :dateTo
              and (:model is null or m.model = :model)
            group by u.id, u.email, m.model
            order by m.model asc
            """)
    List<UsageSummaryResponse> findUsageByUserIdAndOrganizationId(
            @Param("userId") UUID userId,
            @Param("organizationId") UUID organizationId,
            @Param("dateFrom") Instant dateFrom,
            @Param("dateTo") Instant dateTo,
            @Param("model") String model
    );
}