#!/usr/bin/env python3
"""Негативные тесты: обойти security gates и допуск к production нельзя."""
import importlib.util
from pathlib import Path
import shutil
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('policy', HERE / 'check-infra-security-policy.py')
policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)
ROOT = HERE.parents[1]
REQUIRED = [
    'infra/postgres/base-image.txt',
    'infra/postgres/Dockerfile',
    'infra/postgres/noble-candidate/Dockerfile',
    'infra/scripts/build-prod.sh',
    'infra/scripts/test-postgres-bootstrap.sh',
    'infra/docker-compose.yml',
    '.github/workflows/docker-ci.yml',
    '.github/workflows/production-release.yml',
    '.github/workflows/security-ci.yml',
    '.github/workflows/ci-policy.yml',
]


class InfraPolicyTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = Path(self.tmp.name)
        for relative in REQUIRED:
            dest = self.repo / relative
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / relative, dest)

    def alter(self, path, old, new):
        file = self.repo / path
        content = file.read_text(encoding='utf-8')
        self.assertIn(old, content)
        file.write_text(content.replace(old, new, 1), encoding='utf-8')

    def test_reference_passes(self):
        policy.assert_policy(self.repo)

    def test_tampered_base_digest_fails(self):
        self.alter('infra/postgres/base-image.txt', '@sha256:', '@bad:')
        with self.assertRaises(AssertionError):
            policy.assert_policy(self.repo)

    def test_changed_main_pg_base_fails(self):
        self.alter('infra/postgres/Dockerfile',
                   'FROM ${UBUNTU_IMAGE} AS postgres-core',
                   'FROM debian:bookworm AS postgres-core')
        with self.assertRaises(AssertionError):
            policy.assert_policy(self.repo)

    def test_ignore_unfixed_fails(self):
        self.alter('.github/workflows/docker-ci.yml',
                   'ignore-unfixed: false', 'ignore-unfixed: true')
        with self.assertRaises(AssertionError):
            policy.assert_policy(self.repo)

    def test_missing_smoke_fails(self):
        self.alter('.github/workflows/docker-ci.yml',
                   'bash infra/scripts/test-postgres-bootstrap.sh safeai-postgres:ci',
                   'echo skipped')
        with self.assertRaises(AssertionError):
            policy.assert_policy(self.repo)

    def test_missing_restore_approval_fails(self):
        self.alter('.github/workflows/production-release.yml',
                   '"$APPROVED_IMAGE" == "$POSTGRES_IMAGE"',
                   '"$APPROVED_IMAGE" != "$POSTGRES_IMAGE"')
        with self.assertRaises(AssertionError):
            policy.assert_policy(self.repo)

    def test_trivy_version_change_fails(self):
        self.alter('.github/workflows/production-release.yml',
                   'version: v0.76.2', 'version: v0.70.0')
        with self.assertRaises(AssertionError):
            policy.assert_policy(self.repo)

    def test_critical_ignore_fails(self):
        path = self.repo / '.github/.trivyignore'
        path.write_text('CVE-2026-6653\n', encoding='utf-8')
        with self.assertRaises(AssertionError):
            policy.assert_policy(self.repo)


if __name__ == '__main__':
    unittest.main()
