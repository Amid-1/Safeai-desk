import importlib.util
import subprocess
import tempfile
import unittest
from datetime import timedelta
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[2] / 'infra/scripts/audit-checkpoint-archive.py'
SPEC = importlib.util.spec_from_file_location('archive', SCRIPT)
archive = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(archive)
DATASET = 'ac000000-0000-4000-8000-000000000001'
A = 'ac000000-0000-4000-8000-000000000010'
B = 'ac000000-0000-4000-8000-000000000020'
C = 'ac000000-0000-4000-8000-000000000030'


def manifest(rows, previous, timestamp, dataset=DATASET):
    header = archive.checkpoint.make_header(dataset, previous)
    header['captured_at'] = archive.stamp(timestamp)
    return archive.checkpoint.Manifest(archive.line(header) + b''.join(
        archive.line(dict(kind='event', id=event, sha256=digest)) for event, digest in sorted(rows))
        + archive.line(dict(kind='footer', count=len(rows))))


def key(root, name):
    path = root / name
    subprocess.run(['openssl', 'genpkey', '-algorithm', 'ED25519', '-out', str(path)], check=True)
    path.chmod(0o600)
    return path


class FakeS3(archive.S3):
    def __init__(self, trust, clock):
        super().__init__('safeai-test-archive', '123456789012', trust, 1)
        self.clock, self.objects = clock, {}
        self.markers = False
        self.mode = 'COMPLIANCE'
        self.versioning = 'Enabled'
        self.tags = [{'Key': 'SafeAIPurpose', 'Value': 'audit-checkpoint'}]

    def call(self, command, *args):
        def value(option):
            return args[args.index(option) + 1]

        if command == 'get-bucket-versioning':
            return {'Status': self.versioning}
        if command == 'get-object-lock-configuration':
            return {'ObjectLockConfiguration': {'ObjectLockEnabled': 'Enabled', 'Rule': {
                'DefaultRetention': {'Days': 1, 'Mode': self.mode}}}}
        if command == 'get-bucket-tagging':
            return {'TagSet': self.tags}
        if command == 'list-object-versions':
            return {'IsTruncated': False, 'Versions': [obj['metadata'] for obj in
                    self.objects.get(value('--prefix'), [])], 'DeleteMarkers': [{}] if self.markers else []}
        name = value('--key')
        if command == 'put-object':
            assert value('--if-none-match') == '*' and name not in self.objects
            assert value('--object-lock-mode') == 'COMPLIANCE'
            assert value('--server-side-encryption') == 'AES256'
            data = Path(value('--body')).read_bytes()
            assert value('--checksum-sha256') == archive.b64(bytes.fromhex(archive.sha(data)))
            version = 'v' + str(len(self.objects) + 1)
            self.objects[name] = [dict(data=data, metadata=dict(Key=name, VersionId=version,
                IsLatest=True, LastModified=archive.stamp(self.clock)),
                retention=dict(Mode='COMPLIANCE', RetainUntilDate=value('--object-lock-retain-until-date')))]
            return {'VersionId': version}
        obj = next(o for o in self.objects[name] if o['metadata']['VersionId'] == value('--version-id'))
        version = obj['metadata']['VersionId']
        if command == 'get-object-retention':
            return {'Retention': obj['retention']}
        if command == 'head-object':
            return {'VersionId': version, 'ContentLength': len(obj['data']), 'ServerSideEncryption': 'AES256'}
        if command == 'get-object':
            Path(args[-1]).write_bytes(obj['data'])
            return {'VersionId': version}
        raise AssertionError(command)


class ArchiveTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        self.root = key(root, 'root.pem')
        self.new = key(root, 'new.pem')
        self.retention = key(root, 'retention.pem')
        self.foreign = key(root, 'foreign.pem')
        self.a, self.b, self.c = [(event, archive.sha(event.encode())) for event in (A, B, C)]
        self.clock = archive.now() - timedelta(minutes=2)
        genesis = manifest([self.a, self.b], None, self.clock - timedelta(seconds=5))
        self.trust = archive.Trust(DATASET, archive.sha(archive.private_der(self.root)),
                                  archive.sha(archive.private_der(self.retention)), genesis.sha256)
        self.store = FakeS3(self.trust, self.clock)
        self.record = archive.create_envelope(dict(kind='bundle', sequence=0, previous=None, permit=None),
                                             genesis, self.root, self.trust)
        self.pin = self.publish(self.record)

    def publish(self, record, prior=None):
        archive.validate_candidate(self.store, record, prior, self.trust)
        return self.store.store(record)

    def bundle(self, rows, prior=None, pin=None, signer=None, old=None, permit=None, delta=10):
        prior, pin = prior or self.record, pin or self.pin
        snapshot = manifest(rows, prior.manifest.sha256, self.clock + timedelta(seconds=delta))
        return archive.create_envelope(dict(kind='bundle', sequence=pin['sequence'] + 1,
            previous=pin, permit=permit), snapshot, signer or self.root, self.trust, old)

    def approval(self, rows=None, items=None, signer=None, expires=None):
        snapshot = manifest(rows or [self.a, self.b], self.record.manifest.sha256,
                            self.clock + timedelta(seconds=2))
        return archive.create_envelope(dict(kind='permit', sequence=1, previous=self.pin,
            items=items or [list(self.a)], issued_at=archive.stamp(self.clock + timedelta(seconds=3)),
            expires_at=archive.stamp(expires or archive.now() + timedelta(days=1)),
            policy_evidence_sha256='a' * 64), snapshot, signer or self.retention, self.trust)

    def test_genesis_pinned_and_rotation(self):
        self.store.preflight()
        rotated = self.bundle([self.a, self.b, self.c], signer=self.new, old=self.root)
        self.store.clock += timedelta(seconds=15)
        pin = self.publish(rotated, self.pin)
        latest, deleted = archive.verify_chain(self.store, pin, self.trust)
        self.assertEqual(latest.sha256, rotated.sha256)
        self.assertEqual(deleted, set())
        bad_trust = archive.Trust(DATASET, self.trust.root, self.trust.retention, '0' * 64)
        with self.assertRaises(archive.Invalid):
            archive.validate_link(self.record, None, None, bad_trust)

    def test_rotation_without_old_key_or_wrong_key(self):
        for old in (None, self.foreign):
            with self.subTest(old=old), self.assertRaises(archive.Invalid):
                self.publish(self.bundle([self.a, self.b], signer=self.new, old=old), self.pin)

    def test_same_key_with_extra_rotation_proof_rejected(self):
        with self.assertRaises(archive.Invalid):
            self.publish(self.bundle([self.a, self.b], old=self.root), self.pin)

    def test_tamper_and_unapproved_deletion(self):
        for rows in ([self.b], [(A, '0' * 64), self.b]):
            with self.subTest(rows=rows), self.assertRaises(archive.Invalid):
                self.publish(self.bundle(rows), self.pin)
        obj = self.store.objects[self.store.key('bundle', 0)][0]
        obj['data'] = obj['data'].replace(self.a[1].encode(), b'0' * 64)
        with self.assertRaises(archive.Invalid):
            self.store.read(self.pin)

    def test_permit_exact_delete_and_resurrection(self):
        permit = self.approval(rows=[self.a, self.b, self.c])
        self.store.clock += timedelta(seconds=5)
        approval_pin = self.publish(permit, self.pin)
        for rows in ([self.b], [self.c], [self.a, self.b, self.c]):
            with self.subTest(rows=rows), self.assertRaises(archive.Invalid):
                self.publish(self.bundle(rows, permit=approval_pin), self.pin)
        with self.assertRaises(archive.Invalid):
            self.publish(self.bundle([self.a, self.b, self.c]), self.pin)
        after = self.bundle([self.b, self.c], permit=approval_pin)
        self.store.clock += timedelta(seconds=10)
        tip = self.publish(after, self.pin)
        self.assertEqual({A}, archive.verify_chain(self.store, tip, self.trust)[1])
        with self.assertRaises(archive.Invalid):
            self.publish(self.bundle([self.a, self.b, self.c], prior=after, pin=tip, delta=25), tip)

    def test_forged_unknown_and_expired_permit(self):
        with self.assertRaises(archive.Invalid):
            self.approval(signer=self.foreign)
        with self.assertRaises(archive.Invalid):
            self.publish(self.approval(items=[list(self.c)]), self.pin)
        with self.assertRaises(archive.Invalid):
            self.publish(self.approval(expires=self.clock + timedelta(seconds=4)), self.pin)

    def test_permit_must_be_anchored_before_capture(self):
        self.store.clock += timedelta(seconds=5)
        approval_pin = self.publish(self.approval(), self.pin)
        with self.assertRaises(archive.Invalid):
            self.publish(self.bundle([self.b], permit=approval_pin, delta=4), self.pin)

    def test_delete_markers_duplicate_versions_and_no_overwrites(self):
        with self.assertRaises(archive.Invalid):
            self.store.store(self.record)
        self.store.markers = True
        with self.assertRaises(archive.Invalid):
            self.store.read(self.pin)
        self.store.markers = False
        objects = self.store.objects[self.store.key('bundle', 0)]
        objects.append(objects[0])
        with self.assertRaises(archive.Invalid):
            self.store.read(self.pin)

    def test_bucket_preflight_and_version_retention(self):
        self.store.versioning = 'Suspended'
        with self.assertRaises(archive.Invalid):
            self.store.preflight()
        self.store.versioning = 'Enabled'
        self.store.mode = 'GOVERNANCE'
        with self.assertRaises(archive.Invalid):
            self.store.preflight()
        self.store.mode = 'COMPLIANCE'
        self.store.tags = []
        with self.assertRaises(archive.Invalid):
            self.store.preflight()
        with self.assertRaises(archive.Invalid):
            self.store.read(dict(self.pin, account='000000000000'))
        self.store.objects[self.store.key('bundle', 0)][0]['retention']['Mode'] = 'GOVERNANCE'
        with self.assertRaises(archive.Invalid):
            self.store.read(self.pin)

    def test_recovery_same_version_and_bytes_only(self):
        recovered = self.store.confirm(self.record, self.pin['version_id'], archive.now() + timedelta(days=1))
        self.assertEqual(self.pin, recovered)
        with self.assertRaises(archive.Invalid):
            self.store.confirm(self.record, 'other-version', archive.now())
        with self.assertRaises(archive.Invalid):
            self.store.read(dict(self.pin, sha256='0' * 64))

    def test_seal_cli_requires_previous_stage_signature_and_pin(self):
        root = self.root.parent
        source, signature, output = root / 'genesis.jsonl', root / 'genesis.sig', root / 'genesis.bundle'
        source.write_bytes(self.record.manifest.data)
        signature.write_bytes(archive.checkpoint.signature_operation(self.record.manifest, self.root))
        options = ['python3', str(SCRIPT), '--dataset', DATASET, '--root-key-sha256', self.trust.root,
                   '--retention-key-sha256', self.trust.retention, '--genesis-manifest-sha256', self.trust.genesis,
                   'seal', '--manifest', str(source), '--private-key', str(self.root), '--output', str(output),
                   '--genesis-signature', str(signature), '--genesis-sha256', self.trust.genesis]
        bad = root / 'bad.sig'
        bad.write_bytes(b'0' * 64)
        wrong = [str(bad) if part == str(signature) else part for part in options]
        self.assertEqual(1, subprocess.run(wrong, capture_output=True).returncode)
        self.assertFalse(output.exists())
        result = subprocess.run(options, capture_output=True)
        self.assertEqual(0, result.returncode, result.stderr.decode())
        self.assertEqual(self.record.sha256, archive.load_envelope(output, self.trust).sha256)
        self.assertEqual(1, subprocess.run(options, capture_output=True).returncode)


if __name__ == '__main__':
    unittest.main()
