#SafeAI-Desk/.github/scripts/check-performance-reports.py
#!/usr/bin/env python3
"""Require evidence of every baseline performance scenario, with no skips."""
from collections import Counter
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

REQUIRED = {
    'ru.safeai.gateway.chat.integration.ChatErasureBatchBoundaryV86IntegrationTest': {
        'smallBoundariesFullyErase': 5,
        'exactlyTenThousandCompletesWithoutOffByOne': 1,
        'tenThousandAndOneIsRejectedBeforeIntentAndWithoutAnyRedaction': 1,
    },
    'ru.safeai.gateway.usage.repository.UsageExplainAnalyzePerformanceIntegrationTest': {
        'explainAnalyzeCoversGlobalModelOrganizationAndUserShapes': 1,
    },
    'ru.safeai.gateway.usage.repository.UsagePerformanceIntegrationTest': {
        'rollupBackedReportsFor366DaysStayWithinConfiguredSla': 1,
        'concurrentAdmissionControlProtectsDatabasePool': 1,
    },
}


def verify(directory: Path) -> int:
    total = 0
    for suite, needed in REQUIRED.items():
        path = directory / f'TEST-{suite}.xml'
        root = ET.parse(path).getroot()
        if root.tag != 'testsuite' or root.get('name') != suite:
            raise ValueError(f'{path.name}: unexpected suite')
        for field in ('failures', 'errors', 'skipped'):
            if root.get(field) != '0':
                raise ValueError(f'{suite}: {field} must be zero')
        cases = root.findall('testcase')
        if int(root.get('tests', '-1')) != len(cases):
            raise ValueError(f'{suite}: inconsistent test count')
        seen = set()
        counts = Counter()
        for case in cases:
            name = case.get('name', '')
            if not name or name in seen or case.get('classname') != suite:
                raise ValueError(f'{suite}: invalid or duplicate testcase {name}')
            seen.add(name)
            if any(case.find(tag) is not None for tag in ('failure', 'error', 'skipped')):
                raise ValueError(f'{suite}: unsuccessful testcase {name}')
            method = name.split('(', 1)[0].split('[', 1)[0]
            counts[method] += 1
        for method, minimum in needed.items():
            if counts[method] < minimum:
                raise ValueError(f'{suite}: missing {method} ({counts[method]}/{minimum})')
        total += len(cases)
        print(f'{suite}: {len(cases)} passed')
    return total


if __name__ == '__main__':
    try:
        if len(sys.argv) != 2:
            raise ValueError('Usage: check-performance-reports.py SUREFIRE_DIRECTORY')
        total = verify(Path(sys.argv[1]))
    except (OSError, ValueError, ET.ParseError) as exc:
        raise SystemExit(f'PERFORMANCE GATE BLOCKED: {exc}') from exc
    print(f'PERFORMANCE GATE OK: {total} tests, no failures, errors or skips')
