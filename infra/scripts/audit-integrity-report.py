#!/usr/bin/env python3
"""Read-only independent audit verifier; publish a bounded signed status, never evidence."""
import argparse
import importlib.util
import os
import subprocess
import sys
import tempfile
from datetime import timedelta
from pathlib import Path
from types import SimpleNamespace

SPEC = importlib.util.spec_from_file_location('archive', Path(__file__).with_name('audit-checkpoint-archive.py'))
archive = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(archive)
checkpoint = archive.checkpoint
require = archive.require
MAX_COUNT = 10_000_000


def compare(storage, pin, trust, psql, signer_fingerprint):
    storage.preflight()
    prior, tombstones = archive.verify_chain(storage, pin, trust)
    require(archive.sha(prior.key) != signer_fingerprint, 'independent status key required')
    require(storage.version('bundle', pin['sequence'] + 1) is None, 'tip is not current')
    require(storage.version('permit', pin['sequence'] + 1) is None, 'pending retention authorization')
    with tempfile.TemporaryDirectory(prefix='safeai-audit-report-') as directory:
        path = Path(directory) / 'current'
        checkpoint.capture(SimpleNamespace(dataset=trust.dataset, previous_sha256=prior.manifest.sha256,
                                           output=path, psql=psql))
        current = checkpoint.read_manifest(path)
    require(archive.date(pin['stored_at']) <= current.captured_at <= archive.now(), 'invalid capture chronology')
    result = assess(prior.manifest, current, tombstones)
    storage.read(pin)
    require(storage.version('bundle', pin['sequence'] + 1) is None, 'tip advanced during verification')
    require(storage.version('permit', pin['sequence'] + 1) is None, 'retention authorization appeared')
    return result


def assess(prior, current, tombstones):
    counts = checkpoint.compare(prior, current)
    resurrected = sum(event in tombstones for event, _ in current.events())
    violations = counts['missing'] + counts['changed'] + resurrected
    pending = counts['new_unanchored']
    require(all(0 <= value <= MAX_COUNT for value in (prior.count, violations, pending)), 'count bound')
    state = 'VIOLATION' if violations else ('PENDING' if pending else 'VERIFIED')
    return current.captured_at, state, prior.count, violations, pending, 'AVAILABLE'


def encode(dataset, result, private):
    checked_at, state, checked, violations, pending, availability = result
    fields = ['safeai-audit-status-v1', dataset, str(int(checked_at.timestamp())), state,
              str(checked), str(violations), str(pending), availability]
    message = ('\n'.join(fields) + '\n').encode('ascii')
    with tempfile.TemporaryDirectory(prefix='safeai-status-sign-') as directory:
        path = Path(directory) / 'message'
        path.write_bytes(message)
        signature = checkpoint.openssl('pkeyutl', '-sign', '-rawin', '-inkey', str(private), '-in', str(path))
    require(len(signature) == 64, 'invalid status signature')
    return message + archive.b64(signature).encode('ascii') + b'\n'


def publish(path, data):
    # Mount the directory read-only in backend; replacing the inode is intentional.
    fd, name = tempfile.mkstemp(prefix='.audit-status-', dir=path.parent)
    temporary = Path(name)
    try:
        with os.fdopen(fd, 'wb') as output:
            os.fchmod(output.fileno(), 0o640)
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('dataset', 'root-key-sha256', 'retention-key-sha256', 'genesis-manifest-sha256', 'bucket', 'account'):
        parser.add_argument('--' + name, required=True)
    for name in ('pin', 'status-private-key', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--retention-days', type=int, default=1095)
    parser.add_argument('--psql', default='psql')
    args = parser.parse_args()
    try:
        trust = archive.Trust(args.dataset, args.root_key_sha256, args.retention_key_sha256, args.genesis_manifest_sha256)
        fingerprint = archive.sha(archive.private_der(args.status_private_key))
        require(fingerprint not in (trust.root, trust.retention), 'independent status key required')
        try:
            storage = archive.S3(args.bucket, args.account, trust, args.retention_days)
            pin = archive.read_pin(args.pin, trust, 'bundle')
            result = compare(storage, pin, trust, args.psql, fingerprint)
            require(archive.now() - result[0] < timedelta(minutes=15), 'verification exceeded freshness window')
        except (archive.Invalid, OSError, ValueError, TypeError, KeyError, subprocess.SubprocessError):
            result = (archive.now(), 'UNAVAILABLE', -1, -1, -1, 'UNKNOWN')
        publish(args.output, encode(args.dataset, result, args.status_private_key))
        print('AUDIT INTEGRITY: ' + result[1])
        return {'VERIFIED': 0, 'PENDING': 3, 'VIOLATION': 2, 'UNAVAILABLE': 1}[result[1]]
    except (archive.Invalid, OSError, ValueError, subprocess.SubprocessError):
        print('AUDIT INTEGRITY: report not published', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
