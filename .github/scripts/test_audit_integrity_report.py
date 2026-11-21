import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import test_audit_checkpoint_archive as fixtures

SCRIPT = Path(__file__).resolve().parents[2] / 'infra/scripts/audit-integrity-report.py'
SPEC = importlib.util.spec_from_file_location('report', SCRIPT)
report = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(report)


class ReportTest(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.ArchiveTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.key = fixtures.key(self.root, 'status.pem')
        self.fingerprint = report.archive.sha(report.archive.private_der(self.key))

    def compare(self, rows):
        f = self.fixture

        def capture(args):
            args.output.write_bytes(fixtures.manifest(rows, args.previous_sha256, report.archive.now()).data)

        with patch.object(report.checkpoint, 'capture', capture):
            return report.compare(f.store, f.pin, f.trust, 'psql', self.fingerprint)

    def test_verified_pending_deleted_and_changed(self):
        f = self.fixture
        self.assertEqual(self.compare([f.a, f.b])[1:], ('VERIFIED', 2, 0, 0, 'AVAILABLE'))
        self.assertEqual(self.compare([f.a, f.b, f.c])[1:], ('PENDING', 2, 0, 1, 'AVAILABLE'))
        self.assertEqual(self.compare([f.b])[1:], ('VIOLATION', 2, 1, 0, 'AVAILABLE'))
        self.assertEqual(self.compare([(fixtures.A, '0' * 64), f.b])[1:], ('VIOLATION', 2, 1, 0, 'AVAILABLE'))
        current = fixtures.manifest([f.a, f.b, f.c], f.record.manifest.sha256, report.archive.now())
        self.assertEqual(report.assess(f.record.manifest, current, {fixtures.C})[1:], ('VIOLATION', 2, 1, 1, 'AVAILABLE'))

    def test_fails_on_noncompliance_and_advanced_tip(self):
        f = self.fixture
        f.store.mode = 'GOVERNANCE'
        with self.assertRaises(fixtures.archive.Invalid):
            self.compare([f.a, f.b])
        f.store.mode = 'COMPLIANCE'
        f.publish(f.bundle([f.a, f.b, f.c]), f.pin)
        with self.assertRaises(report.archive.Invalid):
            self.compare([f.a, f.b])

    def test_signed_atomic_publication_and_tamper(self):
        f = self.fixture
        data = report.encode(fixtures.DATASET, self.compare([f.a, f.b]), self.key)
        target = self.root / 'status.txt'
        report.publish(target, data)
        self.assertEqual(target.read_bytes(), data)
        self.assertEqual(target.stat().st_mode & 0o777, 0o640)
        import base64
        lines = data.splitlines(keepends=True)
        self.assertEqual(len(lines), 9)
        message, signature, public = [self.root / p for p in ('message', 'signature', 'public')]
        message.write_bytes(b''.join(lines[:-1]))
        signature.write_bytes(base64.b64decode(lines[-1]))
        public.write_bytes(report.archive.private_der(self.key))
        cmd = ['openssl', 'pkeyutl', '-verify', '-rawin', '-pubin', '-keyform', 'DER', '-inkey',
               str(public), '-in', str(message), '-sigfile', str(signature)]
        subprocess.run(cmd, check=True, capture_output=True)
        message.write_bytes(message.read_bytes().replace(b'VERIFIED', b'VIOLATION'))
        self.assertNotEqual(subprocess.run(cmd, capture_output=True).returncode, 0)
        unavailable = (report.archive.now(), 'UNAVAILABLE', -1, -1, -1, 'UNKNOWN')
        report.publish(target, report.encode(fixtures.DATASET, unavailable, self.key))
        self.assertIn(b'UNAVAILABLE\n-1\n-1\n-1\nUNKNOWN\n', target.read_bytes())

    def test_java_consumer_verifies_actual_python_report(self):
        f = self.fixture
        result = self.compare([f.a, f.b])
        target = self.root / 'status.txt'
        report.publish(target, report.encode(fixtures.DATASET, result, self.key))
        source = Path(__file__).resolve().parents[2] / 'backend/src/main/java/ru/safeai/gateway/audit'
        harness = self.root / 'WireProbe.java'
        harness.write_text('''package ru.safeai.gateway.audit.service;
import java.time.Instant;
public class WireProbe {
    public static void main(String[] args) {
        var result = AuditIntegrityReport.read(args[0], args[1], args[2], Instant.ofEpochSecond(Long.parseLong(args[3])));
        System.out.println(result.state() + ":" + result.checkedEvents());
    }
}
''', encoding='utf-8')
        compiler = self.root / 'CompileProbe.java'
        compiler.write_text('''import javax.tools.ToolProvider;
class CompileProbe {
    public static void main(String[] args) {
        System.exit(ToolProvider.getSystemJavaCompiler().run(null, null, null, args));
    }
}
''', encoding='utf-8')
        subprocess.run(['java', str(compiler), '-d', str(self.root), str(source / 'dto/AuditIntegrityStatus.java'),
                        str(source / 'service/AuditIntegrityReport.java'), str(harness)], check=True, timeout=60)
        cmd = ['java', '-cp', str(self.root), 'ru.safeai.gateway.audit.service.WireProbe', str(target),
               fixtures.DATASET, report.archive.b64(report.archive.private_der(self.key)), str(int(result[0].timestamp()))]
        self.assertEqual(subprocess.check_output(cmd, text=True, timeout=30).strip(), 'VERIFIED:2')
        target.write_bytes(target.read_bytes().replace(b'VERIFIED', b'PENDING'))
        self.assertEqual(subprocess.check_output(cmd, text=True, timeout=30).strip(), 'INVALID:null')


if __name__ == '__main__':
    unittest.main()
