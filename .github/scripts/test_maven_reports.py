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
    ("ru.safeai.gateway.usage.repository.UsageExplainAnalyzePerformanceIntegrationTest",
     "explainAnalyzeCoversGlobalModelOrganizationAndUserShapes"),
    ("ru.safeai.gateway.usage.repository.UsagePerformanceIntegrationTest",
     "concurrentAdmissionControlProtectsDatabasePool"),
    ("ru.safeai.gateway.usage.repository.UsagePerformanceIntegrationTest",
     "rollupBackedReportsFor366DaysStayWithinConfiguredSla"),
]

class MavenReportGateTest(unittest.TestCase):
    def call(self, mode, skipped, total):
        with tempfile.TemporaryDirectory() as t:
            path = Path(t) / "TEST-case.xml"
            root = Element("testsuite")
            for i in range(total):
                SubElement(root, "testcase", classname="test.Clazz", name=f"test_{i}")
            for clazz, name in BASE:
                case = SubElement(root, "testcase", classname=clazz, name=name)
                if (clazz, name) in skipped:
                    SubElement(case, "skipped", message="system property disabled")
            ElementTree(root).write(path, encoding="unicode")
            return subprocess.run([sys.executable, str(CHECKER), mode, "--reports", t],
                                  capture_output=True, text=True)

    def test_standard_exact_baseline(self):
        self.assertEqual(self.call("standard", set(BASE), 2000).returncode, 0)

    def test_standard_reject_new_skip(self):
        self.assertNotEqual(self.call("standard", set(), 2000).returncode, 0)

    def test_standard_reject_too_few(self):
        self.assertNotEqual(self.call("standard", set(BASE), 12).returncode, 0)

    def test_performance_selected_baseline(self):
        self.assertEqual(self.call("performance", set(), 0).returncode, 0)

    def test_performance_reject_skipped(self):
        self.assertNotEqual(self.call("performance", {BASE[0]}, 0).returncode, 0)

if __name__ == "__main__":
    unittest.main()
