package ru.safeai.gateway.usage.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import ru.safeai.gateway.usage.dto.UsageDailySummaryResponse;
import ru.safeai.gateway.usage.dto.UsageDataQualityResponse;
import ru.safeai.gateway.usage.dto.UsageModelSummaryResponse;
import ru.safeai.gateway.usage.dto.UsageSummaryResponse;
import ru.safeai.gateway.usage.dto.UsageUserSummaryResponse;

import java.util.List;

public interface UsageQueryRepository {

    Slice<UsageSummaryResponse> findSummary(
            UsageQueryCriteria criteria,
            UsageQueryPlan plan,
            Pageable pageable
    );

    Slice<UsageUserSummaryResponse> findUsers(
            UsageQueryCriteria criteria,
            UsageQueryPlan plan,
            Pageable pageable
    );

    List<UsageModelSummaryResponse> findModels(
            UsageQueryCriteria criteria,
            UsageQueryPlan plan
    );

    List<UsageDailySummaryResponse> findDaily(
            UsageQueryCriteria criteria,
            UsageQueryPlan plan
    );

    UsageDataQualityResponse findDataQuality(
            UsageQueryCriteria criteria,
            UsageQueryPlan plan
    );
}
