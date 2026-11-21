#!/usr/bin/env python3
"""External audit evidence checkpoints; no database or signing-key writes."""
import argparse
import csv
import hashlib
import io
import json
import os
import re
import stat
import subprocess
import sys
import tempfile
import uuid
from datetime import datetime, timezone
from pathlib import Path

VERSION = "safeai-audit-checkpoint-v1"
ROW_DOMAIN = b"SafeAI audit event PostgreSQL16 JSONB v1\x00"
SIGN_DOMAIN = b"SafeAI audit checkpoint signature v1\x00"
ED25519_SPKI_PREFIX = bytes.fromhex("302a300506032b6570032100")
MAX_MANIFEST = 128 * 1024 * 1024
EXPORT_SQL = Path(__file__).with_name("audit-checkpoint-export.sql")


class Invalid(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise Invalid(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def is_hash(value):
    return isinstance(value, str) and re.fullmatch("[0-9a-f]{64}", value) is not None


def is_uuid(value):
    try:
        return isinstance(value, str) and str(uuid.UUID(value)) == value
    except ValueError:
        return False


def line(record):
    return (json.dumps(record, sort_keys=True, separators=(",", ":"), ensure_ascii=True) + "\n").encode("ascii")


def parse_line(raw):
    try:
        record = json.loads(raw)
        require(isinstance(record, dict) and line(record) == raw, "noncanonical manifest record")
        return record
    except (UnicodeError, ValueError, TypeError, RecursionError) as exc:
        raise Invalid("invalid manifest record") from exc


def make_header(dataset, previous):
    require(is_uuid(dataset), "invalid dataset UUID")
    require(previous is None or is_hash(previous), "invalid previous digest")
    return {"kind": "header", "format": VERSION, "dataset": dataset,
            "checkpoint": str(uuid.uuid4()), "previous_sha256": previous,
            "captured_at": datetime.now(timezone.utc).isoformat(timespec="microseconds")}


def export_rows(stream, output, header):
    """Hash each exact JSONB record outside PostgreSQL. No payload in the manifest."""
    output.write(line(header))
    csv.field_size_limit(4 * 1024 * 1024)
    previous_id = ""
    count = 0
    ended = False
    for row in csv.reader(stream, strict=True):
        require(not ended and len(row) == 2, "invalid or trailing database export record")
        event_id, payload = row
        if event_id == "END":
            require(payload == str(count), "database export count mismatch")
            ended = True
            continue
        require(is_uuid(event_id) and event_id > previous_id, "invalid or duplicate event identifier")
        try:
            fields = json.loads(payload, parse_int=str, parse_float=str)
        except (UnicodeError, ValueError, RecursionError) as exc:
            raise Invalid("invalid audit row encoding") from exc
        require(isinstance(fields, list) and len(fields) == 11 and fields[0] == event_id,
                "unexpected audit row projection")
        output.write(line({"kind": "event", "id": event_id, "sha256": digest(ROW_DOMAIN + payload.encode("utf-8"))}))
        require(output.tell() <= MAX_MANIFEST - 128, "manifest exceeds size limit")
        count += 1
        previous_id = event_id
    require(ended, "incomplete database export")
    output.write(line({"kind": "footer", "count": count}))
    return count


def write_once(path, writer):
    """Publish only a complete file, without overriding any existing path."""
    path = Path(path)
    with tempfile.NamedTemporaryFile(dir=path.parent, prefix=".audit-checkpoint-", delete=False) as handle:
        temp = Path(handle.name)
        try:
            writer(handle)
            handle.flush()
            os.fsync(handle.fileno())
            os.link(temp, path)
        finally:
            temp.unlink()


def capture(args):
    header = make_header(args.dataset, args.previous_sha256)
    require(not args.output.exists(), "output already exists")

    def writer(output):
        env = dict(os.environ, PGCLIENTENCODING="UTF8")
        # Never print PostgreSQL errors (they could contain data or connection details).
        with subprocess.Popen([args.psql, "-X", "-q", "-w", "-v", "ON_ERROR_STOP=1",
                               "-f", str(EXPORT_SQL)], stdout=subprocess.PIPE,
                              stderr=subprocess.DEVNULL, text=True, encoding="utf-8",
                              env=env, stdin=subprocess.DEVNULL) as process:
            try:
                export_rows(process.stdout, output, header)
                require(process.wait() == 0, "PostgreSQL export failed")
            finally:
                if process.poll() is None:
                    process.kill()
                    process.wait()

    write_once(args.output, writer)
    manifest = read_manifest(args.output)
    print(f"CAPTURED (UNSIGNED): count={manifest.count} sha256={manifest.sha256}")


class Manifest:
    def __init__(self, data):
        self.data = data
        self.sha256 = digest(data)
        stream = io.BytesIO(data)
        self.header = parse_line(stream.readline())
        header = self.header
        require(set(header) == {"kind", "format", "dataset", "checkpoint", "previous_sha256", "captured_at"}
                and header["kind"] == "header" and header["format"] == VERSION
                and is_uuid(header["dataset"]) and is_uuid(header["checkpoint"])
                and (header["previous_sha256"] is None or is_hash(header["previous_sha256"])),
                "unexpected manifest header")
        captured = header["captured_at"]
        require(isinstance(captured, str) and re.fullmatch(
            r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}\+00:00", captured) is not None,
            "invalid capture timestamp")
        try:
            self.captured_at = datetime.fromisoformat(captured)
        except ValueError as exc:
            raise Invalid("invalid capture timestamp") from exc
        previous_id = ""
        self.count = 0
        for raw in stream:
            record = parse_line(raw)
            if record.get("kind") == "footer":
                require(set(record) == {"kind", "count"} and type(record["count"]) is int
                        and record["count"] == self.count and stream.read(1) == b"",
                        "invalid manifest footer")
                return
            require(set(record) == {"kind", "id", "sha256"} and record["kind"] == "event"
                    and is_uuid(record["id"]) and record["id"] > previous_id
                    and is_hash(record["sha256"]), "invalid manifest event")
            previous_id = record["id"]
            self.count += 1
        raise Invalid("manifest footer missing")

    def events(self):
        stream = io.BytesIO(self.data)
        stream.readline()
        for raw in stream:
            record = json.loads(raw)
            if record["kind"] == "footer":
                break
            yield record["id"], record["sha256"]


def read_manifest(path):
    with Path(path).open("rb") as handle:
        data = handle.read(MAX_MANIFEST + 1)
    require(len(data) <= MAX_MANIFEST, "manifest exceeds size limit")
    return Manifest(data)


def openssl(*args):
    result = subprocess.run(["openssl", *args], stdin=subprocess.DEVNULL,
                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=False)
    require(result.returncode == 0, "OpenSSL operation failed")
    return result.stdout


def public_der(path, private=False):
    flags = ["-in", str(path), "-pubout"] if private else ["-pubin", "-in", str(path)]
    der = openssl("pkey", *flags, "-outform", "DER")
    require(len(der) == 44 and der.startswith(ED25519_SPKI_PREFIX), "Ed25519 key required")
    return der


def signature_message(manifest):
    return SIGN_DOMAIN + bytes.fromhex(manifest.sha256)


def signature_operation(manifest, key, signature=None):
    with tempfile.TemporaryDirectory(prefix="safeai-audit-crypto-") as directory:
        message = Path(directory) / "message.bin"
        message.write_bytes(signature_message(manifest))
        if signature is None:
            signed = openssl("pkeyutl", "-sign", "-rawin", "-inkey", str(key), "-in", str(message))
            require(len(signed) == 64, "invalid Ed25519 signature length")
            return signed
        require(len(signature) == 64, "invalid Ed25519 signature length")
        sig_file = Path(directory) / "signature.bin"
        sig_file.write_bytes(signature)
        openssl("pkeyutl", "-verify", "-rawin", "-pubin", "-inkey", str(key),
                "-in", str(message), "-sigfile", str(sig_file))


def authenticate(path, signature, public_key, expected, dataset):
    require(is_hash(expected), "independently pinned SHA-256 required")
    require(is_uuid(dataset), "independently pinned dataset UUID required")
    manifest = read_manifest(path)
    require(manifest.sha256 == expected, "checkpoint does not match pinned digest (possible rollback)")
    require(manifest.header["dataset"] == dataset, "checkpoint dataset mismatch")
    public_der(public_key)
    with Path(signature).open("rb") as handle:
        signed = handle.read(65)
    signature_operation(manifest, public_key, signed)
    return manifest


def compare(prior, current):
    require(prior.header["dataset"] == current.header["dataset"], "dataset mismatch")
    require(current.header["previous_sha256"] == prior.sha256, "checkpoint ancestry mismatch")
    require(current.header["checkpoint"] != prior.header["checkpoint"]
            and current.captured_at >= prior.captured_at, "checkpoint chronology mismatch")
    old_iter, new_iter = iter(prior.events()), iter(current.events())
    old, new = next(old_iter, None), next(new_iter, None)
    missing = changed = added = matches = 0
    while old is not None or new is not None:
        if new is None or (old is not None and old[0] < new[0]):
            missing += 1
            old = next(old_iter, None)
        elif old is None or new[0] < old[0]:
            added += 1
            new = next(new_iter, None)
        else:
            if old[1] == new[1]:
                matches += 1
            else:
                changed += 1
            old, new = next(old_iter, None), next(new_iter, None)
    return {"matched": matches, "missing": missing, "changed": changed, "new_unanchored": added}


def sign(args):
    current = read_manifest(args.manifest)
    require(is_uuid(args.dataset) and current.header["dataset"] == args.dataset, "dataset mismatch")
    if args.genesis:
        require(current.header["previous_sha256"] is None
                and args.previous_signature is None and args.expected_previous_sha256 is None,
                "genesis cannot have a predecessor")
    else:
        require(args.previous_signature is not None and args.expected_previous_sha256 is not None,
                "previous signature and independently pinned digest required")
        previous = authenticate(args.previous, args.previous_signature, args.public_key,
                                args.expected_previous_sha256, args.dataset)
        result = compare(previous, current)
        require(result["missing"] == 0 and result["changed"] == 0,
                "cannot sign: previous evidence missing or changed")
    metadata = args.private_key.lstat()
    require(stat.S_ISREG(metadata.st_mode) and metadata.st_mode & 0o077 == 0,
            "private key must be a regular file with owner-only permissions")
    require(public_der(args.private_key, private=True) == public_der(args.public_key), "signing key mismatch")
    signed = signature_operation(current, args.private_key)
    signature_operation(current, args.public_key, signed)
    write_once(args.signature, lambda handle: handle.write(signed))
    print(f"SIGNED: count={current.count} sha256={current.sha256}")


def verify(args):
    prior = authenticate(args.checkpoint, args.signature, args.public_key, args.expected_sha256, args.dataset)
    current = read_manifest(args.current)
    result = compare(prior, current)
    print(json.dumps(result, sort_keys=True))
    if result["missing"] or result["changed"]:
        print("FAIL: anchored evidence missing or changed", file=sys.stderr)
        return 2
    print("PASS: anchored evidence matches; new events are not independently authenticated")
    return 0


def parser():
    root = argparse.ArgumentParser(description=__doc__)
    sub = root.add_subparsers(dest="command", required=True)
    capture_parser = sub.add_parser("capture")
    capture_parser.add_argument("--dataset", required=True)
    capture_parser.add_argument("--previous-sha256")
    capture_parser.add_argument("--output", type=Path, required=True)
    capture_parser.add_argument("--psql", default="psql", help="trusted local psql executable")
    sign_parser = sub.add_parser("sign")
    sign_parser.add_argument("--dataset", required=True)
    for name in ("manifest", "private-key", "public-key", "signature"):
        sign_parser.add_argument("--" + name, type=Path, required=True)
    mode = sign_parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--genesis", action="store_true")
    mode.add_argument("--previous", type=Path)
    sign_parser.add_argument("--previous-signature", type=Path)
    sign_parser.add_argument("--expected-previous-sha256")
    verify_parser = sub.add_parser("verify")
    verify_parser.add_argument("--dataset", required=True)
    for name in ("checkpoint", "signature", "public-key", "current"):
        verify_parser.add_argument("--" + name, type=Path, required=True)
    verify_parser.add_argument("--expected-sha256", required=True)
    return root


def main():
    args = parser().parse_args()
    try:
        if args.command == "capture":
            capture(args)
        elif args.command == "sign":
            sign(args)
        else:
            return verify(args)
        return 0
    except Invalid as exc:
        print(f"BLOCKED: {exc}", file=sys.stderr)
        return 1
    except (OSError, UnicodeError, csv.Error, subprocess.SubprocessError):
        print("BLOCKED: checkpoint I/O, database or cryptographic operation failed", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
