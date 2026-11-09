"""Fail-closed validation for the GitHub Production publish manifest."""
import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location(
    'publish_manifest', Path(__file__).with_name('verify-production-manifest.py')
)
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)
SHA = 'a' * 40
RUN_ID = '1234567'
REPO = 'Amid-1/Safeai-desk'


def fixture():
    run = {
        'id': int(RUN_ID), 'head_sha': SHA, 'head_branch': 'main',
        'status': 'completed', 'conclusion': 'success',
        'event': 'workflow_dispatch',
        'path': '.github/workflows/production-release.yml',
    }
    manifest = {'release_sha': SHA, 'publish_run_id': int(RUN_ID)}
    for name in ('backend', 'nginx', 'postgres'):
        manifest[f'{name}_image'] = f'ghcr.io/amid-1/safeai-{name}@sha256:' + 'b' * 64
    return run, manifest


class ManifestTests(unittest.TestCase):
    def test_valid(self):
        self.assertEqual(len(module.verify(*fixture(), SHA, RUN_ID, REPO)), 3)

    def test_different_sha(self):
        run, manifest = fixture()
        run['head_sha'] = 'c' * 40
        with self.assertRaises(ValueError):
            module.verify(run, manifest, SHA, RUN_ID, REPO)

    def test_failed_publish(self):
        run, manifest = fixture()
        run['conclusion'] = 'failure'
        with self.assertRaises(ValueError):
            module.verify(run, manifest, SHA, RUN_ID, REPO)

    def test_wrong_workflow(self):
        run, manifest = fixture()
        run['path'] = '.github/workflows/untrusted.yml'
        with self.assertRaises(ValueError):
            module.verify(run, manifest, SHA, RUN_ID, REPO)

    def test_wrong_run_id(self):
        run, manifest = fixture()
        manifest['publish_run_id'] = 55
        with self.assertRaises(ValueError):
            module.verify(run, manifest, SHA, RUN_ID, REPO)

    def test_image_not_immutable(self):
        run, manifest = fixture()
        manifest['postgres_image'] = 'ghcr.io/amid-1/safeai-postgres:latest'
        with self.assertRaises(ValueError):
            module.verify(run, manifest, SHA, RUN_ID, REPO)

    def test_wrong_owner(self):
        run, manifest = fixture()
        manifest['postgres_image'] = 'ghcr.io/attacker/safeai-postgres@sha256:' + 'b' * 64
        with self.assertRaises(ValueError):
            module.verify(run, manifest, SHA, RUN_ID, REPO)

    def test_extra_field(self):
        run, manifest = fixture()
        manifest['override'] = 'true'
        with self.assertRaises(ValueError):
            module.verify(run, manifest, SHA, RUN_ID, REPO)

    def test_not_main(self):
        run, manifest = fixture()
        run['head_branch'] = 'feature'
        with self.assertRaises(ValueError):
            module.verify(run, manifest, SHA, RUN_ID, REPO)


if __name__ == '__main__':
    unittest.main()
