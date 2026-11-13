from __future__ import annotations

import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path
from xml.etree import ElementTree

HERE = Path(__file__).parent


def load(name: str, filename: str):
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    module = importlib.util.module_from_spec(spec)
    assert spec and spec.loader
    spec.loader.exec_module(module)
    return module


flyway = load("safeai_flyway_guard", "check-flyway-migrations.py")
coverage = load("safeai_coverage_guard", "check-backend-coverage.py")


class FlywayGuardTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        self.directory = self.repo / flyway.MIGRATIONS
        self.directory.mkdir(parents=True)
        subprocess.run(["git", "init", "-q"], cwd=self.repo, check=True)
        subprocess.run(["git", "config", "user.email", "ci@example.invalid"], cwd=self.repo, check=True)
        subprocess.run(["git", "config", "user.name", "CI"], cwd=self.repo, check=True)

    def write(self, name: str, sql: str):
        path = self.directory / name
        path.write_text(sql, encoding="utf-8")
        return path

    def commit(self, message: str):
        subprocess.run(["git", "add", "."], cwd=self.repo, check=True)
        subprocess.run(["git", "commit", "-qm", message], cwd=self.repo, check=True)

    def test_accepts_contiguous_versions(self):
        self.write("V1__init.sql", "SELECT 1;")
        self.write("V2__next.sql", "SELECT 2;")
        self.assertEqual(len(flyway.validate_files(self.repo)), 2)

    def test_rejects_gap(self):
        self.write("V1__init.sql", "SELECT 1;")
        self.write("V3__later.sql", "SELECT 3;")
        with self.assertRaisesRegex(ValueError, "Пропущены"):
            flyway.validate_files(self.repo)

    def test_rejects_duplicate_version(self):
        self.write("V1__first.sql", "SELECT 1;")
        self.write("V1__second.sql", "SELECT 2;")
        with self.assertRaisesRegex(ValueError, "Повтор"):
            flyway.validate_files(self.repo)

    def test_rejects_leading_zero(self):
        self.write("V01__bad.sql", "SELECT 1;")
        with self.assertRaisesRegex(ValueError, "Некорректное"):
            flyway.validate_files(self.repo)

    def test_rejects_edit_of_published_migration(self):
        self.write("V1__init.sql", "SELECT 1;")
        self.commit("published migration")
        self.write("V1__init.sql", "SELECT 2;")
        self.write("V2__next.sql", "SELECT 3;")
        with self.assertRaisesRegex(ValueError, "Изменена историческая"):
            flyway.validate_immutability(self.repo, "HEAD")

    def test_accepts_append_only_migration(self):
        self.write("V1__init.sql", "SELECT 1;")
        self.commit("published migration")
        self.write("V2__next.sql", "SELECT 2;")
        self.assertEqual(flyway.validate_immutability(self.repo, "HEAD"), (2, 1))


class CoverageGuardTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.xml = Path(self.temp.name) / "jacoco.xml"
        root = ElementTree.Element("report", name="safeai")
        package = ElementTree.SubElement(root, "package", name="ru/safeai/gateway/knowledge")
        ElementTree.SubElement(package, "counter", type="LINE", covered="70", missed="30")
        ElementTree.SubElement(package, "counter", type="BRANCH", covered="30", missed="20")
        ElementTree.ElementTree(root).write(self.xml)

    def test_parses_module_and_total(self):
        result = coverage.coverage_report(self.xml)
        self.assertEqual(result["knowledge"]["line"], 0.7)
        self.assertEqual(result["_total"]["branch"], 0.6)

    def test_rejects_unknown_runtime_module(self):
        tree = ElementTree.parse(self.xml)
        package = ElementTree.SubElement(tree.getroot(), "package", name="ru/safeai/gateway/newmodule")
        ElementTree.SubElement(package, "counter", type="LINE", covered="1", missed="0")
        tree.write(self.xml)
        with self.assertRaisesRegex(ValueError, "Новые runtime-модули"):
            coverage.coverage_report(self.xml)

    def test_rejects_coverage_regression(self):
        current = coverage.coverage_report(self.xml)
        baseline = {"_format": 1, "modules": {
            name: {"line": value["line"], "branch": value["branch"]}
            for name, value in current.items()
        }}
        baseline["modules"]["knowledge"]["line"] = 0.75
        self.assertTrue(coverage.enforce(current, baseline, 0.01))

    def test_accepts_unchanged_baseline(self):
        current = coverage.coverage_report(self.xml)
        baseline = {"_format": 1, "modules": {
            name: {"line": value["line"], "branch": value["branch"]}
            for name, value in current.items()
        }}
        self.assertEqual(coverage.enforce(current, baseline, 0.01), [])


if __name__ == "__main__":
    unittest.main()
