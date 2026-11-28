"""Negative tests for the 7.5F.5 local Redis report gate."""
import contextlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from xml.etree import ElementTree as ET

SPEC = importlib.util.spec_from_file_location(
    "ratelimit_gate", Path(__file__).with_name("check-ratelimit-acceptance.py")
)
gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gate)


class RateLimitReportGateTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.directory = Path(self.tmp.name)
        for name, methods in gate.REQUIRED.items():
            suite = gate.PREFIX + name
            root = ET.Element("testsuite", name=suite, tests=str(len(methods)),
                              errors="0", failures="0", skipped="0")
            for method in methods:
                ET.SubElement(root, "testcase", classname=suite, name=method)
            ET.ElementTree(root).write(self.directory / f"TEST-{suite}.xml")
        self.target = self.directory / f"TEST-{gate.PREFIX}RateLimitProductionAcceptanceIT.xml"

    def verify(self):
        with contextlib.redirect_stdout(io.StringIO()):
            return gate.verify(self.directory)

    def mutate(self, action):
        tree = ET.parse(self.target)
        action(tree.getroot())
        tree.write(self.target)

    def test_complete_report_passes(self):
        self.assertEqual(self.verify(), sum(map(len, gate.REQUIRED.values())))

    def test_empty_parentheses_in_junit_method_name_allowed(self):
        def append(root):
            case = root.find("testcase")
            case.set("name", case.get("name") + "()")
        self.mutate(append)
        self.assertEqual(self.verify(), sum(map(len, gate.REQUIRED.values())))

    def test_missing_report_blocks(self):
        self.target.unlink()
        with self.assertRaises(OSError):
            self.verify()

    def test_missing_case_blocks_even_if_suite_count_adjusted(self):
        def remove(root):
            root.remove(root.find("testcase"))
            root.set("tests", str(len(root.findall("testcase"))))
        self.mutate(remove)
        with self.assertRaisesRegex(ValueError, "missing mandatory"):
            self.verify()

    def test_nested_unsuccessful_or_retried_case_blocks(self):
        for tag in ("skipped", "error", "failure", "flakyFailure", "rerunFailure"):
            with self.subTest(tag=tag):
                tree = ET.parse(self.target)
                case = tree.getroot().find("testcase")
                child = ET.SubElement(case, tag)
                tree.write(self.target)
                with self.assertRaisesRegex(ValueError, "unsuccessful or retried"):
                    self.verify()
                case.remove(child)
                tree.write(self.target)

    def test_nonzero_suite_counters_block(self):
        self.mutate(lambda root: root.set("skipped", "1"))
        with self.assertRaisesRegex(ValueError, "skipped"):
            self.verify()

    def test_count_mismatch_blocks(self):
        self.mutate(lambda root: root.set("tests", "0"))
        with self.assertRaisesRegex(ValueError, "inconsistent"):
            self.verify()

    def test_duplicate_case_blocks(self):
        def duplicate(root):
            case = root.find("testcase")
            ET.SubElement(root, "testcase", **case.attrib)
            root.set("tests", str(len(root.findall("testcase"))))
        self.mutate(duplicate)
        with self.assertRaisesRegex(ValueError, "duplicate"):
            self.verify()

    def test_wrong_class_blocks(self):
        self.mutate(lambda root: root.find("testcase").set("classname", "forged.Class"))
        with self.assertRaisesRegex(ValueError, "invalid testcase"):
            self.verify()

    def test_malformed_xml_blocks(self):
        self.target.write_text("<testsuite", encoding="utf-8")
        with self.assertRaises(ET.ParseError):
            self.verify()


if __name__ == "__main__":
    unittest.main()
