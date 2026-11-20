#SafeAI-Desk/.github/scripts/test_infra_security_policy.py
#!/usr/bin/env python3
"""Негативные тесты: обойти security gates и допуск к production нельзя."""
import importlib.util
from pathlib import Path
import re
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
    '.github/workflows/production-deploy.yml',
    '.github/workflows/security-ci.yml',
    '.github/workflows/ci-policy.yml',
    '.github/workflows/performance-ci.yml',
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

    def alter_publish_needs(self, replacement):
        path = self.repo / '.github/workflows/production-release.yml'
        content = path.read_text(encoding='utf-8')
        job = re.search(r'(?ms)^  publish:\n(.*?)(?=^  \w[\w-]*:|\Z)', content)
        self.assertIsNotNone(job)
        block, count = re.subn(r'(?m)^    needs: \[[^\]\n]+\][ \t]*$',
                               '    needs: ' + replacement, job[1])
        self.assertEqual(1, count)
        path.write_text(content[:job.start(1)] + block + content[job.end(1):],
                        encoding='utf-8')

    def test_reference_passes(self):
        policy.assert_policy(self.repo)

    def test_tampered_base_digest_fails(self):
        self.alter('infra/postgres/base-image.txt', '@sha256:', '@bad:')
        with self.assertRaises(AssertionError):
            policy.assert_policy(self.repo)

    def test_old_go_builder_rejected_in_either_dockerfile(self):
        old_go = 'golang:1.27.3-trixie@sha256:c65ecfe613b539ac74c62a4c3b2c27e8f8a6076384c8f27e8a3c0f3be17cc385'
        for path in ('infra/postgres/Dockerfile', 'infra/postgres/noble-candidate/Dockerfile'):
            with self.subTest(path=path):
                self.alter(path, f'ARG GOSU_BUILDER_IMAGE={policy.GO}', f'ARG GOSU_BUILDER_IMAGE={old_go}')
                with self.assertRaisesRegex(AssertionError, 'GOSU_BUILDER_IMAGE'):
                    policy.assert_policy(self.repo)
                self.alter(path, f'ARG GOSU_BUILDER_IMAGE={old_go}', f'ARG GOSU_BUILDER_IMAGE={policy.GO}')

    def test_missing_gosu_binary_version_check_fails(self):
        self.alter('infra/postgres/Dockerfile',
                   'test "$(go version /out/gosu)" = \'/out/gosu: go1.27.4\';',
                   'echo skipped;')
        with self.assertRaisesRegex(AssertionError, 'go version /out/gosu'):
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

    def test_missing_runtime_acl_contract_fails(self):
        self.alter('.github/workflows/docker-ci.yml',
                   'bash infra/scripts/test-runtime-acl.sh safeai-postgres:ci safeai-backend:ci',
                   'echo skipped')
        with self.assertRaises(AssertionError):
            policy.assert_policy(self.repo)

    def test_missing_release_runtime_contract_fails(self):
        self.alter('.github/workflows/production-release.yml',
                   'bash infra/scripts/test-runtime-acl.sh', 'echo skipped')
        with self.assertRaises(AssertionError):
            policy.assert_policy(self.repo)

    def test_release_without_performance_fails(self):
        self.alter_publish_needs('[preflight, provider-data-assurance]')
        with self.assertRaises(AssertionError):
            policy.assert_policy(self.repo)

    def test_release_without_provider_assurance_fails(self):
        self.alter_publish_needs('[preflight, performance]')
        with self.assertRaises(AssertionError):
            policy.assert_policy(self.repo)

    def test_release_with_reordered_dependencies_passes(self):
        self.alter_publish_needs('[provider-data-assurance, preflight, performance]')
        policy.assert_policy(self.repo)

    def test_release_with_nonblocking_provider_assurance_fails(self):
        self.alter('.github/workflows/production-release.yml',
                   '    uses: ./.github/workflows/provider-data-assurance.yml',
                   '    continue-on-error: true\n    uses: ./.github/workflows/provider-data-assurance.yml')
        with self.assertRaises(AssertionError):
            policy.assert_policy(self.repo)

    def test_release_with_decoy_needs_fails(self):
        self.alter_publish_needs('[preflight, provider-data-assurance]')
        path = self.repo / '.github/workflows/production-release.yml'
        with path.open('a', encoding='utf-8') as handle:
            handle.write('\n  decoy:\n    needs: [preflight, performance, provider-data-assurance]\n')
        with self.assertRaises(AssertionError):
            policy.assert_policy(self.repo)

    def test_release_always_bypassing_dependencies_fails(self):
        self.alter('.github/workflows/production-release.yml',
                   '    if: github.ref == \'refs/heads/main\'\n    runs-on: ubuntu-latest\n    timeout-minutes: 150',
                   '    if: always()\n    runs-on: ubuntu-latest\n    timeout-minutes: 150')
        with self.assertRaises(AssertionError):
            policy.assert_policy(self.repo)

    def test_performance_disabled_fails(self):
        self.alter('.github/workflows/performance-ci.yml',
                   '-Dsafeai.usage.performance=true', '-Dsafeai.usage.performance=false')
        with self.assertRaises(AssertionError):
            policy.assert_policy(self.repo)

    def test_performance_missing_report_gate_fails(self):
        self.alter('.github/workflows/performance-ci.yml',
                   'run: python3 .github/scripts/check-performance-reports.py backend/target/surefire-reports',
                   'run: echo skipped')
        with self.assertRaises(AssertionError):
            policy.assert_policy(self.repo)

    def test_missing_restore_approval_fails(self):
        self.alter('.github/workflows/production-deploy.yml',
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
