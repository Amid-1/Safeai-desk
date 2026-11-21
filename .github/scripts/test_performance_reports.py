#SafeAI-Desk/.github/scripts/test_performance_reports.py
import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location(
    'performance_reports', Path(__file__).with_name('check-performance-reports.py'))
reports = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reports)


class PerformanceReportsTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.directory = Path(tmp.name)
        for suite, methods in reports.REQUIRED.items():
            root = ET.Element('testsuite', name=suite, tests=str(sum(methods.values())),
                              failures='0', errors='0', skipped='0')
            for method, count in methods.items():
                for i in range(1, count + 1):
                    name = f'{method}(int)[{i}]' if count > 1 else method
                    ET.SubElement(root, 'testcase', name=name, classname=suite)
            ET.ElementTree(root).write(self.path(suite), encoding='utf-8')
        self.suite = next(iter(reports.REQUIRED))

    def path(self, suite):
        return self.directory / f'TEST-{suite}.xml'

    def change(self, edit):
        path = self.path(self.suite)
        tree = ET.parse(path)
        edit(tree.getroot())
        tree.write(path, encoding='utf-8')

    def test_success(self):
        self.assertEqual(10, reports.verify(self.directory))

    def test_missing_report(self):
        self.path(self.suite).unlink()
        with self.assertRaises(OSError):
            reports.verify(self.directory)

    def test_skipped(self):
        self.change(lambda root: root.set('skipped', '1'))
        with self.assertRaises(ValueError):
            reports.verify(self.directory)

    def test_failure(self):
        self.change(lambda root: root.set('failures', '1'))
        with self.assertRaises(ValueError):
            reports.verify(self.directory)

    def test_hidden_skip(self):
        self.change(lambda root: ET.SubElement(root.find('testcase'), 'skipped'))
        with self.assertRaises(ValueError):
            reports.verify(self.directory)

    def test_missing_method(self):
        self.change(lambda root: root.findall('testcase')[-1].set('name', 'other'))
        with self.assertRaises(ValueError):
            reports.verify(self.directory)

    def test_duplicate_case(self):
        self.change(lambda root: root.findall('testcase')[1].set(
            'name', root.find('testcase').get('name')))
        with self.assertRaises(ValueError):
            reports.verify(self.directory)

    def test_count_mismatch(self):
        self.change(lambda root: root.set('tests', '99'))
        with self.assertRaises(ValueError):
            reports.verify(self.directory)

    def test_missing_parameter_case(self):
        def remove(root):
            root.remove(root.find('testcase'))
            root.set('tests', '6')
        self.change(remove)
        with self.assertRaises(ValueError):
            reports.verify(self.directory)


if __name__ == '__main__':
    unittest.main()
