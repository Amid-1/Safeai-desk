#SafeAI-Desk/.github/scripts/test_maven_reports.py
#!/usr/bin/env python3
# SafeAI-Desk/.github/scripts/test_maven_reports.py
"""Офлайн-регрессии защиты от неполных и пропущенных тестов."""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from xml.etree.ElementTree import Element, SubElement, ElementTree

CHECKER = Path(__file__).with_name("check-maven-reports.py")
BASE = [
    ("ru.safeai.gateway.usage.repository.UsagePhysicalExplainPerformanceIntegrationTest",
     "explainActualRepositoryQueriesWithSelectiveTenantAndGlobalRange"),
    ("ru.safeai.gateway.usage.repository.UsageExplainAnalyzePerformanceIntegrationTest",
     "explainAnalyzeCoversGlobalModelOrganizationAndUserShapes"),
    ("ru.safeai.gateway.usage.repository.UsagePerformanceIntegrationTest",
     "concurrentAdmissionControlProtectsDatabasePool"),
    ("ru.safeai.gateway.usage.repository.UsagePerformanceIntegrationTest",
     "rollupBackedReportsFor366DaysStayWithinConfiguredSla"),
]
STANDARD = BASE + [
    ("ru.safeai.gateway.chat.integration.ChatErasureBatchBoundaryV86IntegrationTest",
     "exactlyTenThousandCompletesWithoutOffByOne"),
    ("ru.safeai.gateway.chat.integration.ChatErasureBatchBoundaryV86IntegrationTest",
     "tenThousandAndOneIsRejectedBeforeIntentAndWithoutAnyRedaction"),
]

class MavenReportGateTest(unittest.TestCase):
    def call(self, mode, skipped, total):
        with tempfile.TemporaryDirectory() as t:
            path = Path(t) / "TEST-case.xml"
            root = Element("testsuite")
            for i in range(total):
                name = f"test_{i}"
                case = SubElement(root, "testcase", classname="test.Clazz", name=name)
                if ("test.Clazz", name) in skipped:
                    SubElement(case, "skipped", message="unexpected skip")
            for clazz, name in (STANDARD if mode == "standard" else BASE):
                case = SubElement(root, "testcase", classname=clazz, name=name)
                if (clazz, name) in skipped:
                    SubElement(case, "skipped", message="system property disabled")
            ElementTree(root).write(path, encoding="unicode")
            return subprocess.run([sys.executable, str(CHECKER), mode, "--reports", t],
                                  capture_output=True, text=True)

    def test_standard_exact_baseline(self):
        self.assertEqual(self.call("standard", set(STANDARD), 2000).returncode, 0)

    def test_standard_reject_new_skip(self):
        self.assertNotEqual(self.call("standard", set(STANDARD) | {("test.Clazz", "test_0")}, 2000).returncode, 0)

    def test_standard_reject_missing_expected_skip(self):
        self.assertNotEqual(self.call("standard", set(BASE), 2000).returncode, 0)

    def test_standard_reject_substituted_skip(self):
        skips = set(STANDARD[:-1]) | {("test.Clazz", "test_0")}
        self.assertNotEqual(self.call("standard", skips, 2000).returncode, 0)

    def test_standard_reject_too_few(self):
        self.assertNotEqual(self.call("standard", set(STANDARD), 12).returncode, 0)

    def test_performance_selected_baseline(self):
        self.assertEqual(self.call("performance", set(), 0).returncode, 0)

    def test_performance_reject_skipped(self):
        self.assertNotEqual(self.call("performance", {BASE[0]}, 0).returncode, 0)

if __name__ == "__main__":
    unittest.main()
