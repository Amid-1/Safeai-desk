#!/usr/bin/env python3
"""Негативные проверки встроенного Bash и PostgreSQL-guards."""
import importlib.util
from pathlib import Path
import unittest

HERE = Path(__file__).resolve().parent
MODULE = HERE / 'check-production-deploy-embedded-bash.py'
spec = importlib.util.spec_from_file_location('deploy_syntax', MODULE)
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)
SOURCE = Path(__file__).resolve().parents[2] / checker.WORKFLOW


class ProductionDeployShellTests(unittest.TestCase):
    def setUp(self) -> None:
        self.workflow = SOURCE.read_text(encoding='utf-8')

    def replace_once(self, old: str, new: str) -> None:
        self.assertEqual(self.workflow.count(old), 1)
        self.workflow = self.workflow.replace(old, new, 1)

    def test_current_workflow(self) -> None:
        checker.validate(self.workflow)

    def test_blank_line_after_continuation(self) -> None:
        self.replace_once(
            'printf -v remote_command "$remote_fmt" \\\n',
            'printf -v remote_command "$remote_fmt" \\\n\n',
        )
        with self.assertRaisesRegex(ValueError, 'Пустая строка'):
            checker.validate(self.workflow)

    def test_missing_pg_image_guard(self) -> None:
        self.replace_once(
            '[[ "$current_pg_image" == "$SAFEAI_POSTGRES_IMAGE" ]]',
            '[[ "$current_pg_image" != "$SAFEAI_POSTGRES_IMAGE" ]]',
        )
        with self.assertRaisesRegex(ValueError, 'PostgreSQL image'):
            checker.validate(self.workflow)

    def test_missing_volume_guard(self) -> None:
        self.replace_once('docker volume inspect safeai_postgres_data',
                          'docker volume inspect ignored-volume')
        with self.assertRaisesRegex(ValueError, 'PGDATA volume'):
            checker.validate(self.workflow)

    def test_missing_remote_execution(self) -> None:
        self.replace_once(
            'ssh "${ssh_opts[@]}" "$target" "$remote_command"',
            'echo skip-remote',
        )
        with self.assertRaisesRegex(ValueError, 'SSH запуск'):
            checker.validate(self.workflow)

    def test_invalid_remote_shell(self) -> None:
        self.replace_once('set -Eeuo pipefail\n\n          cleanup() {',
                          'set -Eeuo pipefail\n\n          if then\n          cleanup() {')
        with self.assertRaisesRegex(ValueError, 'Bash syntax'):
            checker.validate(self.workflow)


if __name__ == '__main__':
    unittest.main()
