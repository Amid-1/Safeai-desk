package ru.safeai.gateway.chat.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.chat.entity.ChatTurnEntity;
import ru.safeai.gateway.chat.entity.ChatTurnState;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@SuppressWarnings({
        "SqlResolve",
        "SqlNoDataSourceInspection"
})
public interface ChatTurnRepository
        extends JpaRepository<ChatTurnEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select turn
              from ChatTurnEntity turn
             where turn.session.id = :sessionId
               and turn.clientRequestId = :clientRequestId
            """)
    Optional<ChatTurnEntity> findByIdempotencyKeyForUpdate(
            @Param("sessionId") UUID sessionId,
            @Param("clientRequestId") UUID clientRequestId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select turn
              from ChatTurnEntity turn
             where turn.session.id = :sessionId
               and turn.state = :state
            """)
    Optional<ChatTurnEntity> findSessionTurnByStateForUpdate(
            @Param("sessionId") UUID sessionId,
            @Param("state") ChatTurnState state
    );

    Optional<ChatTurnEntity> findByIdAndSession_IdAndUser_IdAndOrganization_Id(
            UUID id,
            UUID sessionId,
            UUID userId,
            UUID organizationId
    );

    Optional<ChatTurnEntity> findBySession_IdAndClientRequestIdAndUser_IdAndOrganization_Id(
            UUID sessionId,
            UUID clientRequestId,
            UUID userId,
            UUID organizationId
    );


    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update chat_turns
               set provider_call_started_at = :startedAt,
                   updated_at = :startedAt,
                   version = version + 1
             where id = :turnId
               and state = 'PROCESSING'
               and processing_token = :processingToken
               and provider_call_started_at is null
               and lease_until >= :startedAt
            """, nativeQuery = true)
    int markProviderCallStarted(
            @Param("turnId") UUID turnId,
            @Param("processingToken") UUID processingToken,
            @Param("startedAt") Instant startedAt
    );

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update chat_turns
               set lease_until = :newLeaseUntil,
                   updated_at = :observedAt,
                   version = version + 1
             where id = :turnId
               and state = 'PROCESSING'
               and processing_token = :processingToken
               and lease_until >= :observedAt
            """, nativeQuery = true)
    int renewLease(
            @Param("turnId") UUID turnId,
            @Param("processingToken") UUID processingToken,
            @Param("observedAt") Instant observedAt,
            @Param("newLeaseUntil") Instant newLeaseUntil
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update chat_turns
               set state = 'SUCCEEDED',
                   assistant_message_id = :assistantMessageId,
                   requested_model = :requestedModel,
                   resolved_model = :resolvedModel,
                   provider_request_id = :providerRequestId,
                   processing_token = null,
                   lease_until = null,
                   outcome_ambiguous = false,
                   completed_at = :completedAt,
                   updated_at = :completedAt,
                   version = version + 1
             where id = :turnId
               and state = 'PROCESSING'
               and processing_token = :processingToken
               and provider_call_started_at is not null
               and lease_until >= :completedAt
            """, nativeQuery = true)
    int markSucceeded(
            @Param("turnId") UUID turnId,
            @Param("processingToken") UUID processingToken,
            @Param("assistantMessageId") UUID assistantMessageId,
            @Param("requestedModel") String requestedModel,
            @Param("resolvedModel") String resolvedModel,
            @Param("providerRequestId") String providerRequestId,
            @Param("completedAt") Instant completedAt
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update chat_turns
               set state = 'FAILED',
                   provider = coalesce(:provider, provider),
                   requested_model = coalesce(:requestedModel, requested_model),
                   provider_request_id = :providerRequestId,
                   provider_error_type = :providerErrorType,
                   failure_code = :failureCode,
                   processing_token = null,
                   lease_until = null,
                   outcome_ambiguous = false,
                   completed_at = :completedAt,
                   updated_at = :completedAt,
                   version = version + 1
             where id = :turnId
               and state = 'PROCESSING'
               and processing_token = :processingToken
               and provider_call_started_at is not null
               and lease_until >= :completedAt
            """, nativeQuery = true)
    int markFailed(
            @Param("turnId") UUID turnId,
            @Param("processingToken") UUID processingToken,
            @Param("provider") String provider,
            @Param("requestedModel") String requestedModel,
            @Param("providerRequestId") String providerRequestId,
            @Param("providerErrorType") String providerErrorType,
            @Param("failureCode") String failureCode,
            @Param("completedAt") Instant completedAt
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update chat_turns
               set state = 'AMBIGUOUS',
                   provider = coalesce(:provider, provider),
                   requested_model = coalesce(:requestedModel, requested_model),
                   provider_request_id = :providerRequestId,
                   provider_error_type = :providerErrorType,
                   failure_code = :failureCode,
                   processing_token = null,
                   lease_until = null,
                   outcome_ambiguous = true,
                   completed_at = :completedAt,
                   updated_at = :completedAt,
                   version = version + 1
             where id = :turnId
               and state = 'PROCESSING'
               and processing_token = :processingToken
               and provider_call_started_at is not null
            """, nativeQuery = true)
    int markAmbiguous(
            @Param("turnId") UUID turnId,
            @Param("processingToken") UUID processingToken,
            @Param("provider") String provider,
            @Param("requestedModel") String requestedModel,
            @Param("providerRequestId") String providerRequestId,
            @Param("providerErrorType") String providerErrorType,
            @Param("failureCode") String failureCode,
            @Param("completedAt") Instant completedAt
    );


    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update chat_turns
               set state = 'FAILED',
                   provider_error_type = :providerErrorType,
                   failure_code = :failureCode,
                   processing_token = null,
                   lease_until = null,
                   outcome_ambiguous = false,
                   completed_at = :completedAt,
                   updated_at = :completedAt,
                   version = version + 1
             where id = :turnId
               and state = 'PROCESSING'
               and processing_token = :processingToken
               and provider_call_started_at is null
            """, nativeQuery = true)
    int markFailedBeforeProviderCall(
            @Param("turnId") UUID turnId,
            @Param("processingToken") UUID processingToken,
            @Param("providerErrorType") String providerErrorType,
            @Param("failureCode") String failureCode,
            @Param("completedAt") Instant completedAt
    );

    @Modifying(flushAutomatically = true)
    @Query(value = """
            update chat_turns
               set state = 'FAILED',
                   provider_error_type = coalesce(
                       provider_error_type,
                       'PROVIDER_CALL_NOT_STARTED'
                   ),
                   failure_code = coalesce(
                       failure_code,
                       'STALE_BEFORE_PROVIDER_CALL'
                   ),
                   processing_token = null,
                   lease_until = null,
                   outcome_ambiguous = false,
                   completed_at = :now,
                   updated_at = :now,
                   version = version + 1
             where id = :turnId
               and state = 'PROCESSING'
               and provider_call_started_at is null
               and lease_until <= :now
            """, nativeQuery = true)
    int markExpiredBeforeProviderFailed(
            @Param("turnId") UUID turnId,
            @Param("now") Instant now
    );

    @Modifying(flushAutomatically = true)
    @Query(value = """
            update chat_turns
               set state = 'AMBIGUOUS',
                   provider_error_type = coalesce(provider_error_type, 'STALE_LEASE'),
                   failure_code = coalesce(failure_code, 'STALE_PROCESSING_LEASE'),
                   processing_token = null,
                   lease_until = null,
                   outcome_ambiguous = true,
                   completed_at = :now,
                   updated_at = :now,
                   version = version + 1
             where id = :turnId
               and state = 'PROCESSING'
               and provider_call_started_at is not null
               and lease_until <= :now
            """, nativeQuery = true)
    int markExpiredProcessingAmbiguous(
            @Param("turnId") UUID turnId,
            @Param("now") Instant now
    );
}
