import importlib.util
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("audit_contract", Path(__file__).with_name("check-audit-event-contract.py"))
contract = importlib.util.module_from_spec(spec)
spec.loader.exec_module(contract)


class AuditContractTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        for p in (contract.JAVA, contract.FRONT, contract.MIGRATIONS / "V1__audit.sql"):
            (self.repo / p).parent.mkdir(parents=True, exist_ok=True)
        self.java = self.repo / contract.JAVA
        self.front = self.repo / contract.FRONT
        self.sql = self.repo / contract.MIGRATIONS / "V1__audit.sql"
        self.java.write_text("public enum AuditEventType { EVENT_ONE, EVENT_TWO }", encoding="utf-8")
        self.front.write_text(
            "const AUDIT_EVENT_TYPES = ['EVENT_ONE','EVENT_TWO',] as const\n"
            "const LABELS: Record<AuditEventType, string> = { EVENT_ONE: 'Один', EVENT_TWO: 'Два', }",
            encoding="utf-8",
        )
        self.sql.write_text("INSERT INTO audit_event_types VALUES ('EVENT_ONE'), ('EVENT_TWO');", encoding="utf-8")

    def test_valid(self):
        self.assertEqual(contract.validate(self.repo)["types"], 2)

    def test_new_backend_event_without_frontend(self):
        self.java.write_text("enum AuditEventType { EVENT_ONE, EVENT_TWO, EVENT_THREE }", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "EVENT_THREE"):
            contract.validate(self.repo)

    def test_duplicate_frontend(self):
        self.front.write_text(self.front.read_text().replace("'EVENT_TWO',]", "'EVENT_TWO','EVENT_TWO',]"))
        with self.assertRaisesRegex(ValueError, "повтор"):
            contract.validate(self.repo)

    def test_missing_label(self):
        self.front.write_text(self.front.read_text().replace("EVENT_TWO: 'Два',", ""))
        with self.assertRaisesRegex(ValueError, "Локализация"):
            contract.validate(self.repo)

    def test_unlocalized_label(self):
        self.front.write_text(self.front.read_text().replace("'Два'", "'EVENT_TWO'"))
        with self.assertRaisesRegex(ValueError, "русской"):
            contract.validate(self.repo)

    def test_sql_missing(self):
        self.sql.write_text("SELECT 'EVENT_ONE';")
        with self.assertRaisesRegex(ValueError, "SQL"):
            contract.validate(self.repo)

    def test_unknown_enum_syntax_fails_closed(self):
        self.java.write_text("enum AuditEventType { EVENT_ONE(1), EVENT_TWO(2) }")
        with self.assertRaisesRegex(ValueError, "Нераспознанный"):
            contract.validate(self.repo)

    def test_inventory_separates_runtime_test_and_sql(self):
        runtime = self.java.with_name("Writer.java")
        runtime.write_text("EVENT_ONE EVENT_ONE\nEVENT_TWO_SUFFIX\n", encoding="utf-8")
        tests = self.repo / "backend/src/test/java"
        tests.mkdir(parents=True)
        (tests / "WriterTest.java").write_text("EVENT_ONE\n", encoding="utf-8")
        refs = contract.inventory(self.repo, ["EVENT_ONE", "EVENT_TWO"])
        self.assertEqual(len(refs["EVENT_ONE"]["runtime"]), 1)
        self.assertEqual(len(refs["EVENT_ONE"]["tests"]), 1)
        self.assertEqual(len(refs["EVENT_ONE"]["sql"]), 1)
        self.assertEqual(refs["EVENT_TWO"]["runtime"], [])


if __name__ == "__main__":
    unittest.main()
