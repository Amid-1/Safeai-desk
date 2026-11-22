#!/usr/bin/env python3
"""7.5F.5: require fresh Surefire evidence of the local Redis acceptance matrix."""
from __future__ import annotations

import argparse
from pathlib import Path
import sys
from xml.etree import ElementTree as ET

PREFIX = "ru.safeai.gateway.ratelimit."
REQUIRED = {
    "RateLimitProductionAcceptanceIT": (
        "concurrentLoginUsesOneNormalizedIdentityAndEmitsOneAuditMarker",
        "concurrentDuplicateCompensationCannotErasePreviousNatFailures",
        "concurrentRefreshCountsEveryAttemptWithoutExtendingFirstWindow",
        "concurrentAiOrganizationQuotaIsExactAndOtherTenantIsIndependent",
        "userQuotaDoesNotDebitOrganizationAfterRejection",
        "globalCapacityRejectsBeforeNewAnonymousIdentityAllocation",
        "missingFenceAndWrongHmacFailClosedWithoutAllocatingAnyKeys",
        "redisProcessStoppedMakesAllServicesFailClosed",
    ),
    "RedisFixedWindowRateLimiterIntegrationTest": (
        "blockedIpCannotAllocateKeysForTenThousandFreshEmails",
        "distributedAttackCannotExceedSharedAllocationCapacityAcrossInstances",
        "successfulCompensationCannotRefundAllocationBudgetAndOnlyExpiredReservationsArePruned",
        "allocationGuardRejectsCorruptionBeforeWritingNewCounters",
        "expiredCounterAndReceiptReservationDoesNotBlockNextWindow",
        "refreshBudgetCoversAllSlotsAndRejectsBeforeCreatingIdentity",
        "redisMemoryPressureDoesNotEvictPreviousCountersOrPermitNewLogin",
        "firstNAttemptsAllowedAndNPlusOneBlockedAtomically",
        "compensationPreservesPriorFailuresAndConsumesReceiptOnce",
        "compensationPreservesFailuresOfAnotherUserBehindSameNat",
        "compensationIgnoresReplacementWindowButDecrementsStillActiveWindow",
        "staleMarkerOwnerCannotReleaseReplacementMarker",
        "oneHundredSuccessfulUsersBehindNatDoNotExhaustIpLimit",
        "exceededNotificationIsCreatedOnlyOncePerDimensionAndWindow",
        "exhaustedOrganizationDoesNotConsumeNewUserCounter",
        "exhaustedUserDoesNotConsumeOrganizationCounter",
        "concurrentRequestsCompetingForLastSlotAllowOnlyOne",
        "scriptsRepairPermanentCounterKeysAndAllCreatedKeysHaveTtl",
        "releaseNotificationMarkersDeletesOnlyMarkersCreatedByResult",
    ),
    "RedisFixedWindowRateLimiterIT": (
        "dualReservationIsAtomicUnderConcurrency",
        "repeatedIncrementDoesNotExtendOriginalWindow",
        "malformedSecondCounterFailsBeforeAnyWrites",
        "malformedFirstCounterFailsBeforeAnyWrites",
        "malformedSecondCounterAlsoFailsBeforeTryReservationWrites",
        "malformedIpCounterDoesNotDeleteLoginEmailCounter",
        "oversizedCounterIsRejectedWithoutPartialIncrement",
    ),
    "RedisFixedWindowRateLimiterSingleKeyIntegrationTest": (
        "incrementAndGetCreatesCounterAndPreservesFixedWindow",
        "subsequentIncrementDoesNotResetWindowTtl",
        "concurrentIncrementsLoseNoUpdates",
    ),
    "RateLimitEpochFenceIntegrationTest": (
        "mixedHmacVersionsAndMissingOrPausedFenceNeverAllocateCounters",
        "durableRestartRejectsAdmissionUntilExplicitRebindAndPreservesAllCounters",
        "corruptOrExpiringFenceCannotBeRecreatedByApplication",
    ),
    "RateLimitRedisMonitorIntegrationTest": (
        "observesRealRedisWithoutWritingAndReportsFenceTransitionsAndRecovery",
    ),
    "RateLimitMetricsTest": (
        "exportsCanonicalPrometheusNamesAndHistogram",
        "registersIdleCountersAndBoundsAllCallerLabels",
    ),
    "RateLimitProductionConfigurationValidatorTest": (
        "productionRejectsDisabledLoginLimiter",
        "productionRejectsDisabledRefreshLimiter",
        "productionRejectsDisabledAiLimiterWithoutExplicitEscapeHatch",
    ),
    "RateLimitExceededEventPiiTest": (
        "userEventIsValidWithoutEmail",
        "anonymousLoginEventCarriesFingerprintWithoutRawEmail",
    ),
    "exception.RateLimitExceptionHandlerTest": (
        "handleRateLimitUnavailableReturnsSafe503",
        "handleRateLimitExceededReturns429AndRetryAfterHeader",
    ),
}


def verify(directory: Path) -> int:
    total = 0
    for short_name, required in REQUIRED.items():
        suite = PREFIX + short_name
        path = directory / f"TEST-{suite}.xml"
        root = ET.parse(path).getroot()
        if root.tag != "testsuite" or root.get("name") != suite:
            raise ValueError(f"{path.name}: unexpected suite")
        for field in ("failures", "errors", "skipped"):
            if root.get(field) != "0":
                raise ValueError(f"{suite}: {field} must be zero")
        cases = root.findall("testcase")
        if int(root.get("tests", "-1")) != len(cases):
            raise ValueError(f"{suite}: inconsistent testcase count")
        seen = set()
        for case in cases:
            name = case.get("name", "").removesuffix("()")
            if not name or name in seen or case.get("classname") != suite:
                raise ValueError(f"{suite}: duplicate or invalid testcase {name}")
            seen.add(name)
            if any(case.find(tag) is not None for tag in (
                "failure", "error", "skipped", "flakyFailure", "flakyError", "rerunFailure", "rerunError"
            )):
                raise ValueError(f"{suite}: unsuccessful or retried testcase {name}")
        missing = set(required) - seen
        if missing:
            raise ValueError(f"{suite}: missing mandatory cases {sorted(missing)}")
        total += len(cases)
        print(f"{suite}: {len(cases)} passed")
    return total


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("reports", nargs="?", type=Path, default=Path("backend/target/surefire-reports"))
    parser.add_argument("--classes", action="store_true", help="Maven -Dtest selector")
    args = parser.parse_args()
    if args.classes:
        print(",".join(PREFIX + name for name in REQUIRED))
        return 0
    try:
        passed = verify(args.reports)
    except (OSError, ValueError, ET.ParseError) as exc:
        print(f"RATELIMIT LOCAL ACCEPTANCE BLOCKED: {exc}", file=sys.stderr)
        return 1
    print(f"RATELIMIT LOCAL ACCEPTANCE OK: {passed} tests, no failures/errors/skips/retries")
    print("Scope: local Redis; provider RPM/TPM/concurrency and external failover remain separate gates.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
