#!/usr/bin/env python3
"""Offline regression tests for reports and incremental Java style."""
from __future__ import annotations
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent


def load(filename: str):
    spec = importlib.util.spec_from_file_location(filename.replace('.', '_'), HERE / filename)
    assert spec and spec.loader
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


report = load('quality-report.py')
style = load('check-java-style.py')


class DashboardTests(unittest.TestCase):
    def test_java_jacoco_and_junit(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            xml = root / 'jacoco.xml'
            xml.write_text('''<report><package name="ru/safeai/gateway/knowledge">
<counter type="LINE" covered="3" missed="1"/>
<counter type="BRANCH" covered="1" missed="1"/>
</package></report>''', encoding='utf-8')
            baseline = root / 'baseline.json'
            modules = {k: {'line': None, 'branch': None} for k in report.MODULES}
            modules['_total'] = {'line': 0.6, 'branch': 0.4}
            baseline.write_text(json.dumps({'_format': 1, 'modules': modules}), encoding='utf-8')
            reports = root / 'junit'; reports.mkdir()
            (reports / 'TEST-one.xml').write_text(
                '<testsuite><testcase classname="a.KnowledgeIT" '
                'name="test1"/></testsuite>',
                encoding='utf-8',
            )
            data = report.backend_stats(xml, baseline, reports)
            self.assertEqual(data['overall']['line'], 0.75)
            self.assertEqual(data['overall']['branch'], 0.5)
            self.assertEqual(data['tests']['integration'], 1)
            self.assertIn('<table>', report.html_page(data))

    def test_frontend_groups(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / 'coverage-summary.json'
            entry = {'lines': {'total': 10, 'covered': 8}, 'branches': {'total': 4, 'covered': 2}}
            p.write_text(
                json.dumps({'total': entry, '/repo/frontend/src/features/chat/App.tsx': entry}),
                encoding='utf-8',
            )
            data = report.frontend_stats(p)
            self.assertEqual(data['rows'][0]['name'], 'chat')
            self.assertEqual(data['overall']['line'], 0.8)

    def test_java_style_regression(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)
            file = repo / 'backend/src/main/java/A.java'; file.parent.mkdir(parents=True)
            file.write_bytes(b'class A {\n\tint a;\n}\n')
            before = style.issues(repo)
            self.assertEqual(style.check(before, {'_format': 1, 'issues': before}), [])
            file.write_bytes(b'class A {\n\tint a;\n\tint b;\n}\n')
            self.assertTrue(style.check(style.issues(repo), {'_format': 1, 'issues': before}))

    def test_style_baseline_invalid(self):
        with self.assertRaises(ValueError):
            style.check({}, {'_format': 999, 'issues': {}})


if __name__ == '__main__':
    unittest.main()
