import csv
import importlib.util
import io
import json
import subprocess
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[2] / 'infra/scripts/audit-checkpoint.py'
SPEC = importlib.util.spec_from_file_location('audit_checkpoint', SCRIPT)
audit = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(audit)
DATASET = 'ac000000-0000-4000-8000-000000000001'
A = 'ac000000-0000-4000-8000-000000000010'
B = 'ac000000-0000-4000-8000-000000000020'
C = 'ac000000-0000-4000-8000-000000000030'


def csv_rows(rows, footer=True):
    stream = io.StringIO(newline='')
    writer = csv.writer(stream)
    for event_id, details, correlation in rows:
        writer.writerow([event_id, json.dumps([event_id, None, None, None, None,
                         None, None, 'EVENT', details, '2026-11-20T12:00:00', correlation],
                         ensure_ascii=False)])
    if footer:
        writer.writerow(['END', len(rows)])
    stream.seek(0)
    return stream


class AuditCheckpointTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.private = self.root / 'private.pem'
        self.public = self.root / 'public.pem'
        subprocess.run(['openssl', 'genpkey', '-algorithm', 'ED25519', '-out', str(self.private)], check=True)
        self.private.chmod(0o600)
        subprocess.run(['openssl', 'pkey', '-in', str(self.private), '-pubout', '-out', str(self.public)], check=True)
        self.rows = [(A, {'message': 'личные данные'}, None), (B, {'amount': 12}, C)]
        self.old = self.manifest('old', self.rows)
        self.sig = self.root / 'old.sig'
        self.pin = audit.read_manifest(self.old).sha256
        self.cli('sign', '--genesis', '--manifest', self.old, '--signature', self.sig,
                 '--private-key', self.private, '--public-key', self.public)

    def manifest(self, name, rows, previous=None, dataset=DATASET):
        path = self.root / name
        with path.open('wb') as out:
            audit.export_rows(csv_rows(rows), out, audit.make_header(dataset, previous))
        return path

    def cli(self, command, *args, status=0, dataset=DATASET):
        result = subprocess.run(['python3', str(SCRIPT), command, '--dataset', dataset,
                                 *map(str, args)], capture_output=True, text=True)
        self.assertEqual(status, result.returncode, result.stdout + result.stderr)
        return result.stdout + result.stderr

    def verify(self, current, status=0, **overrides):
        args = {'checkpoint': self.old, 'signature': self.sig, 'public-key': self.public,
                'expected-sha256': self.pin, 'current': current}
        args.update(overrides)
        return self.cli('verify', *(x for item in args.items() for x in ('--' + item[0], item[1])), status=status)

    def advance(self, path, status=0):
        return self.cli('sign', '--manifest', path, '--signature', self.root / 'next.sig',
                        '--private-key', self.private, '--public-key', self.public,
                        '--previous', self.old, '--previous-signature', self.sig,
                        '--expected-previous-sha256', self.pin, status=status)

    def test_valid_signature_new_events_and_no_payload(self):
        current = self.manifest('current', self.rows + [(C, {}, None)], self.pin)
        self.assertIn('"new_unanchored": 1', self.verify(current))
        self.assertNotIn('личные данные'.encode(), self.old.read_bytes())
        self.assertNotIn(b'amount', self.old.read_bytes())
        self.advance(current)
        next_pin = audit.read_manifest(current).sha256
        fresh = self.manifest('fresh', self.rows + [(C, {}, None)], next_pin)
        self.assertIn('"matched": 3', self.verify(fresh, checkpoint=current,
                      signature=self.root / 'next.sig', **{'expected-sha256': next_pin}))

    def test_changed_payload_and_correlation_are_detected(self):
        current = self.manifest('current', [(A, {'message': 'changed'}, None), (B, {'amount': 12}, A)], self.pin)
        self.assertIn('"changed": 2', self.verify(current, status=2))
        self.advance(current, status=1)
        self.assertFalse((self.root / 'next.sig').exists())

    def test_deletion_and_replacement_are_detected(self):
        current = self.manifest('current', [self.rows[1], (C, {}, None)], self.pin)
        self.assertIn('"missing": 1', self.verify(current, status=2))
        self.advance(current, status=1)

    def test_wrong_pin_signature_public_key_and_ancestry(self):
        current = self.manifest('current', self.rows, self.pin)
        self.verify(current, status=1, **{'expected-sha256': '0' * 64})
        broken = self.root / 'broken.sig'
        broken.write_bytes(b'\x00' * 64)
        self.verify(current, status=1, signature=broken)
        other = self.root / 'other.pem'
        other_public = self.root / 'other.pub'
        subprocess.run(['openssl', 'genpkey', '-algorithm', 'ED25519', '-out', str(other)], check=True)
        subprocess.run(['openssl', 'pkey', '-in', str(other), '-pubout', '-out', str(other_public)], check=True)
        self.verify(current, status=1, **{'public-key': other_public})
        wrong = self.manifest('wrong', self.rows, '0' * 64)
        self.verify(wrong, status=1)
        foreign = self.manifest('foreign', self.rows, self.pin, dataset=C)
        self.verify(foreign, status=1)

    def test_tampered_or_truncated_manifest(self):
        current = self.manifest('current', self.rows, self.pin)
        data = current.read_bytes()
        current.write_bytes(data[:-1])
        self.verify(current, status=1)
        current.write_bytes(data + b'{}\n')
        self.verify(current, status=1)
        old_data = self.old.read_bytes()
        self.old.write_bytes(old_data.replace(b'"count":2', b'"count":1'))
        self.verify(current, status=1)

    def test_no_overwrite_and_unsafe_private_key_permissions(self):
        original = self.sig.read_bytes()
        self.cli('sign', '--genesis', '--manifest', self.old, '--signature', self.sig,
                 '--private-key', self.private, '--public-key', self.public, status=1)
        self.assertEqual(original, self.sig.read_bytes())
        current = self.manifest('current', self.rows, self.pin)
        self.private.chmod(0o644)
        self.advance(current, status=1)

    def test_invalid_database_export(self):
        for stream in (csv_rows([self.rows[0], self.rows[0]]), csv_rows(self.rows[::-1]),
                       csv_rows(self.rows, False), io.StringIO('END,99\n'),
                       io.StringIO('END,0\nEND,0\n'), io.StringIO(f'{A},[]\nEND,1\n')):
            with self.subTest(stream=stream.getvalue()):
                with self.assertRaises(audit.Invalid):
                    audit.export_rows(stream, io.BytesIO(), audit.make_header(DATASET, None))

    def test_capture_requires_successful_psql_and_complete_file(self):
        fake = self.root / 'psql'
        output = self.root / 'capture'
        content = csv_rows(self.rows).getvalue()
        for code in (1, 0):
            fake.write_text('#!/usr/bin/env python3\nimport sys\nsys.stdout.write(' + repr(content) + ')\nsys.exit(' + str(code) + ')\n')
            fake.chmod(0o700)
            self.cli('capture', '--psql', fake, '--output', output, status=code)
            self.assertEqual(code == 0, output.exists())
        self.assertEqual(2, audit.read_manifest(output).count)


if __name__ == '__main__':
    unittest.main()
