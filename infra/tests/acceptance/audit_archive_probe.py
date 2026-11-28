#!/usr/bin/env python3
"""Opt-in S3 integration test; creates synthetic locked objects, never deletes them."""
import importlib.util
import os
import subprocess
import tempfile
import time
import uuid
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[2] / 'scripts/audit-checkpoint-archive.py'
SPEC = importlib.util.spec_from_file_location('archive', SCRIPT)
archive = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(archive)


def main():
    archive.require(os.environ.get('SAFEAI_AUDIT_ARCHIVE_STAGING_APPROVAL') ==
                    'I_ACCEPT_IMMUTABLE_STAGING_OBJECTS', 'explicit staging approval required')
    dataset = str(uuid.uuid4())
    event = str(uuid.uuid4())

    def snapshot(previous, include=True):
        header = archive.checkpoint.make_header(dataset, previous)
        row = archive.line(dict(kind='event', id=event, sha256=archive.sha(b'synthetic fixture')))
        return archive.checkpoint.Manifest(archive.line(header) + (row if include else b'')
            + archive.line(dict(kind='footer', count=int(include))))

    with tempfile.TemporaryDirectory() as directory:
        keys = []
        for name in ('root', 'rotated', 'retention'):
            path = Path(directory) / name
            subprocess.run(['openssl', 'genpkey', '-algorithm', 'ED25519', '-out', str(path)], check=True)
            path.chmod(0o600)
            keys.append(path)
        root, rotated, retention = keys
        initial = snapshot(None)
        trust = archive.Trust(dataset, archive.sha(archive.private_der(root)),
                              archive.sha(archive.private_der(retention)), initial.sha256)
        storage = archive.S3(os.environ['SAFEAI_TEST_S3_BUCKET'], os.environ['SAFEAI_TEST_AWS_ACCOUNT_ID'], trust, 1)
        storage.preflight()
        archive.require(any(t.get('Key') == 'SafeAITestOnly' and t.get('Value') == 'true' for t in
                            storage.call('get-bucket-tagging').get('TagSet', [])), 'staging-only bucket required')
        first = archive.create_envelope(dict(kind='bundle', sequence=0, previous=None, permit=None),
                                        initial, root, trust)
        archive.validate_candidate(storage, first, None, trust)
        pin = storage.store(first)
        print('Synthetic immutable archive dataset: ' + dataset, flush=True)
        time.sleep(2)
        second = archive.create_envelope(dict(kind='bundle', sequence=1, previous=pin, permit=None),
                                         snapshot(initial.sha256), rotated, trust, root)
        archive.validate_candidate(storage, second, pin, trust)
        tip = storage.store(second)
        last, deleted = archive.verify_chain(storage, tip, trust)
        archive.require(last.sha256 == second.sha256 and not deleted, 'unexpected archive chain')
        time.sleep(2)
        before = snapshot(second.manifest.sha256)
        permit = archive.create_envelope(dict(kind='permit', sequence=2, previous=tip,
            items=[[event, archive.sha(b'synthetic fixture')]], issued_at=archive.stamp(archive.now()),
            expires_at=archive.stamp(archive.now() + archive.timedelta(hours=1)),
            policy_evidence_sha256=archive.sha(b'synthetic approved retention fixture')), before, retention, trust)
        archive.validate_candidate(storage, permit, tip, trust)
        permit_pin = storage.store(permit)
        time.sleep(2)
        third = archive.create_envelope(dict(kind='bundle', sequence=2, previous=tip, permit=permit_pin),
            snapshot(second.manifest.sha256, include=False), rotated, trust)
        archive.validate_candidate(storage, third, tip, trust)
        final = storage.store(third)
        last, deleted = archive.verify_chain(storage, final, trust)
        archive.require(last.sha256 == third.sha256 and deleted == {event}, 'retention proof mismatch')
        try:
            storage.store(first)
        except archive.Invalid:
            pass
        else:
            raise archive.Invalid('duplicate upload accepted')
        print('PASS: versioned COMPLIANCE archive, exact readback, rotation, retention proof and duplicate refusal')


if __name__ == '__main__':
    main()
