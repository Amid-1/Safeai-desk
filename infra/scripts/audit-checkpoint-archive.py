#!/usr/bin/env python3
"""Independent S3 audit evidence archive. No database writes or automatic deletion."""
from __future__ import annotations

import argparse
import base64
import hashlib
import importlib.util
import io
import json
import os
import re
import stat
import subprocess
import sys
import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('checkpoint', Path(__file__).with_name('audit-checkpoint.py'))
checkpoint = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(checkpoint)
require = checkpoint.require
Invalid = checkpoint.Invalid
sha = checkpoint.digest
line = checkpoint.line
MAX_HEADER = 8 * 1024 * 1024
MAX_OBJECT = checkpoint.MAX_MANIFEST + MAX_HEADER
MAX_STEPS = 2000
MAX_REMOVALS = 50000
DOMAIN = b'SafeAI audit archive signed envelope v1\x00'
FORMAT = 'safeai-audit-archive-v1'


def now():
    return datetime.now(timezone.utc)


def stamp(value):
    return value.astimezone(timezone.utc).isoformat(timespec='microseconds')


def date(value):
    require(isinstance(value, str), 'missing timestamp')
    try:
        parsed = datetime.fromisoformat(value.replace('Z', '+00:00'))
        require(parsed.tzinfo is not None and parsed.utcoffset() == timedelta(0), 'UTC timestamp required')
        return parsed
    except (ValueError, OverflowError) as exc:
        raise Invalid('invalid UTC timestamp') from exc


def b64(value):
    return base64.b64encode(value).decode('ascii')


def unb64(value, size):
    require(isinstance(value, str), 'invalid encoded value')
    try:
        decoded = base64.b64decode(value, validate=True)
    except (ValueError, TypeError) as exc:
        raise Invalid('invalid base64') from exc
    require(len(decoded) == size and b64(decoded) == value, 'unexpected encoded value length')
    return decoded


def key_bytes(value):
    der = unb64(value, 44)
    require(der.startswith(checkpoint.ED25519_SPKI_PREFIX), 'Ed25519 key required')
    return der


def private_der(path):
    meta = path.lstat()
    require(stat.S_ISREG(meta.st_mode) and not meta.st_mode & 0o077, 'private key permissions unsafe')
    return checkpoint.public_der(path, private=True)


def sign_message(fields, private):
    with tempfile.TemporaryDirectory(prefix='safeai-archive-sign-') as directory:
        message = Path(directory) / 'message'
        message.write_bytes(DOMAIN + hashlib.sha256(line(fields)).digest())
        return b64(checkpoint.openssl('pkeyutl', '-sign', '-rawin', '-inkey', str(private), '-in', str(message)))


def verify_message(fields, signature, public):
    with tempfile.TemporaryDirectory(prefix='safeai-archive-verify-') as directory:
        root = Path(directory)
        (root / 'message').write_bytes(DOMAIN + hashlib.sha256(line(fields)).digest())
        (root / 'key').write_bytes(public)
        (root / 'signature').write_bytes(unb64(signature, 64))
        checkpoint.openssl('pkeyutl', '-verify', '-rawin', '-pubin', '-keyform', 'DER',
                           '-inkey', str(root / 'key'), '-in', str(root / 'message'),
                           '-sigfile', str(root / 'signature'))


class Trust:
    def __init__(self, dataset, root, retention, genesis):
        require(checkpoint.is_uuid(dataset), 'invalid dataset')
        require(checkpoint.is_hash(root) and checkpoint.is_hash(retention) and root != retention,
                'independent root and retention key fingerprints required')
        require(checkpoint.is_hash(genesis), 'independent genesis manifest fingerprint required')
        self.dataset, self.root, self.retention, self.genesis = dataset, root, retention, genesis


def validate_pin(pin, trust, kind=None):
    require(isinstance(pin, dict) and set(pin) == {'dataset', 'kind', 'sequence', 'bucket', 'account',
            'sha256', 'version_id', 'retain_until', 'stored_at'}, 'invalid archive receipt')
    require(pin['dataset'] == trust.dataset and pin['kind'] in ('bundle', 'permit')
            and (kind is None or pin['kind'] == kind), 'receipt scope mismatch')
    require(type(pin['sequence']) is int and 0 <= pin['sequence'] <= MAX_STEPS
            and checkpoint.is_hash(pin['sha256']), 'invalid archive sequence or hash')
    require(isinstance(pin['bucket'], str) and re.fullmatch(r'[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]', pin['bucket'])
            and isinstance(pin['account'], str) and re.fullmatch(r'\d{12}', pin['account']), 'invalid storage identity')
    require(isinstance(pin['version_id'], str) and 0 < len(pin['version_id']) <= 1024
            and pin['version_id'] != 'null' and all(32 < ord(c) < 127 for c in pin['version_id']), 'invalid S3 version')
    require(date(pin['retain_until']) > date(pin['stored_at']), 'invalid storage interval')
    return pin


def read_bounded(path, limit):
    with Path(path).open('rb') as stream:
        data = stream.read(limit + 1)
    require(len(data) <= limit, 'file too large')
    return data


def read_pin(path, trust, kind=None):
    return validate_pin(checkpoint.parse_line(read_bounded(path, 8192)), trust, kind)


def write_file(path, contents):
    checkpoint.write_once(path, lambda stream: stream.write(contents))


class Envelope:
    def __init__(self, data, trust):
        require(len(data) <= MAX_OBJECT, 'archive object too large')
        stream = io.BytesIO(data)
        header = stream.readline(MAX_HEADER + 1)
        require(len(header) <= MAX_HEADER, 'archive header too large')
        self.fields = checkpoint.parse_line(header)
        self.data, self.sha256 = data, sha(data)
        payload = stream.read()
        require(len(payload) <= checkpoint.MAX_MANIFEST, 'manifest exceeds size limit')
        self.manifest = checkpoint.Manifest(payload)
        fields = self.fields
        require(fields.get('format') == FORMAT and fields.get('dataset') == trust.dataset
                and self.manifest.header['dataset'] == trust.dataset and fields.get('manifest_sha256') == self.manifest.sha256,
                'archive envelope scope or hash mismatch')
        require(type(fields.get('sequence')) is int and 0 <= fields['sequence'] <= MAX_STEPS, 'invalid sequence')
        self.sequence = fields['sequence']
        self.kind = fields.get('kind')
        common = {'format', 'dataset', 'kind', 'sequence', 'manifest_sha256', 'previous', 'public_key', 'signature'}
        if self.kind == 'bundle':
            require(set(fields) == common | {'old_signature', 'permit'}, 'invalid bundle fields')
        elif self.kind == 'permit':
            require(set(fields) == common | {'items', 'issued_at', 'expires_at', 'policy_evidence_sha256'}, 'invalid permit fields')
        else:
            raise Invalid('invalid envelope kind')
        self.key = key_bytes(fields['public_key'])
        self.unsigned = {key: value for key, value in fields.items() if key not in ('signature', 'old_signature')}
        verify_message(self.unsigned, fields['signature'], self.key)
        if self.kind == 'permit':
            require(sha(self.key) == trust.retention, 'untrusted retention signer')
            require(checkpoint.is_hash(fields['policy_evidence_sha256']), 'policy evidence digest required')
            issued, expires = date(fields['issued_at']), date(fields['expires_at'])
            require(self.manifest.captured_at <= issued < expires <= issued + timedelta(days=7), 'invalid permit validity')
            items = fields['items']
            require(isinstance(items, list) and 0 < len(items) <= MAX_REMOVALS, 'invalid permit items')
            last = ''
            for item in items:
                require(isinstance(item, list) and len(item) == 2 and checkpoint.is_uuid(item[0])
                        and item[0] > last and checkpoint.is_hash(item[1]), 'invalid or duplicate permit item')
                last = item[0]
        else:
            require(sha(self.key) != trust.retention, 'retention key cannot sign a checkpoint')
        if fields['previous'] is not None:
            prior = validate_pin(fields['previous'], trust, 'bundle')
            require(prior['sequence'] + 1 == self.sequence, 'noncontiguous archive sequence')
        else:
            require(self.kind == 'bundle' and self.sequence == 0, 'missing predecessor')

    def matches(self, pin, trust):
        validate_pin(pin, trust, self.kind)
        require(self.sequence == pin['sequence'] and self.sha256 == pin['sha256'], 'pinned archive object changed')


def load_envelope(path, trust):
    return Envelope(read_bounded(path, MAX_OBJECT), trust)


def create_envelope(fields, manifest, private, trust, old_private=None):
    fields = dict(fields, format=FORMAT, dataset=trust.dataset, manifest_sha256=manifest.sha256,
                  public_key=b64(private_der(private)))
    unsigned = dict(fields)
    fields['signature'] = sign_message(unsigned, private)
    if fields['kind'] == 'bundle':
        fields['old_signature'] = sign_message(unsigned, old_private) if old_private else None
    return Envelope(line(fields) + manifest.data, trust)


def differences(previous, current):
    # Existing comparison still fences dataset, parent hash and chronology.
    result = checkpoint.compare(previous, current)
    require(result['changed'] == 0, 'anchored event content changed')
    missing = missing_rows(previous, current)
    require(len(missing) == result['missing'], 'inconsistent comparison')
    return missing


def missing_rows(previous, current):
    old, new = iter(previous.events()), iter(current.events())
    left, right = next(old, None), next(new, None)
    missing = []
    while left is not None:
        if right is None or left[0] < right[0]:
            missing.append(list(left))
            require(len(missing) <= MAX_REMOVALS, 'retention batch exceeds limit')
            left = next(old, None)
        elif right[0] < left[0]:
            right = next(new, None)
        else:
            require(left[1] == right[1], 'anchored event content changed')
            left, right = next(old, None), next(new, None)
    return missing


def validate_permit(permit, previous, previous_pin, trust):
    require(permit.kind == 'permit' and permit.fields['previous'] == previous_pin, 'permit predecessor mismatch')
    previous.matches(previous_pin, trust)
    require(not differences(previous.manifest, permit.manifest), 'pre-deletion snapshot missing anchored evidence')
    require(permit.manifest.captured_at >= date(previous_pin['stored_at']), 'pre-deletion snapshot predates archive')
    selected = dict(permit.fields['items'])
    matched = {event: digest for event, digest in previous.manifest.events() if event in selected}
    require(matched == selected, 'permit includes unknown or altered evidence')


def validate_link(current, previous, previous_pin, trust, permit=None, permit_pin=None):
    require(current.kind == 'bundle', 'expected checkpoint bundle')
    fields = current.fields
    if previous is None:
        require(fields['previous'] is None and fields['permit'] is None and fields['old_signature'] is None
                and current.manifest.header['previous_sha256'] is None and sha(current.key) == trust.root
                and current.manifest.sha256 == trust.genesis,
                'invalid archive genesis')
        return []
    previous.matches(previous_pin, trust)
    require(fields['previous'] == previous_pin, 'archive ancestry mismatch')
    require(current.manifest.captured_at >= date(previous_pin['stored_at']), 'capture predates archived predecessor')
    if current.key == previous.key:
        require(fields['old_signature'] is None, 'unexpected rotation proof')
    else:
        verify_message(current.unsigned, fields['old_signature'], previous.key)
    missing = differences(previous.manifest, current.manifest)
    if permit is None:
        require(fields['permit'] is None and not missing, 'unapproved deletion')
    else:
        require(fields['permit'] == permit_pin and permit_pin is not None, 'permit receipt mismatch')
        permit.matches(permit_pin, trust)
        validate_permit(permit, previous, previous_pin, trust)
        require(missing == permit.fields['items'], 'deletion differs from exact approved set')
        require(missing_rows(permit.manifest, current.manifest) == missing, 'pre-deletion evidence disappeared or changed')
        require(date(permit.fields['issued_at']) <= date(permit_pin['stored_at']) + timedelta(seconds=1)
                and date(permit_pin['stored_at']) + timedelta(seconds=1) <= current.manifest.captured_at
                <= date(permit.fields['expires_at']), 'permit not anchored before capture or expired')
    return [item[0] for item in missing]


class S3:
    """Only exact version reads and conditional COMPLIANCE writes; no delete API."""
    def __init__(self, bucket, account, trust, days, aws='aws'):
        require(isinstance(bucket, str) and re.fullmatch(r'[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]', bucket)
                and re.fullmatch(r'\d{12}', account), 'bucket and owner account required')
        require(type(days) is int and 1 <= days <= 3650, 'invalid archive retention days')
        self.bucket, self.account, self.trust, self.days, self.aws = bucket, account, trust, days, aws

    def call(self, command, *args):
        # No custom endpoints or TLS bypass; approved AWS credentials on publisher only.
        env = {key: value for key, value in os.environ.items() if not key.startswith('AWS_ENDPOINT_URL')}
        env['AWS_IGNORE_CONFIGURED_ENDPOINT_URLS'] = 'true'
        env['AWS_PAGER'] = ''
        env['AWS_MAX_ATTEMPTS'] = '1'
        proc = subprocess.run([self.aws, 's3api', command, '--bucket', self.bucket, '--expected-bucket-owner',
                               self.account, *args, '--no-cli-pager', '--output', 'json'],
                              env=env, capture_output=True, timeout=300)
        require(proc.returncode == 0, 'S3 operation failed: ' + command + '; no success assumed')
        try:
            result = json.loads(proc.stdout or b'{}')
        except ValueError as exc:
            raise Invalid('invalid S3 response') from exc
        require(isinstance(result, dict), 'invalid S3 response')
        return result

    def preflight(self):
        require(self.call('get-bucket-versioning').get('Status') == 'Enabled', 'S3 versioning required')
        config = self.call('get-object-lock-configuration').get('ObjectLockConfiguration', {})
        default = config.get('Rule', {}).get('DefaultRetention', {})
        require(config.get('ObjectLockEnabled') == 'Enabled' and default.get('Mode') == 'COMPLIANCE',
                'default COMPLIANCE required')
        days = default.get('Days', 0)
        years = default.get('Years', 0)
        require(type(days) is int and type(years) is int and days >= 0 and years >= 0
                and (days > 0) != (years > 0) and days + years * 365 >= self.days,
                'bucket default retention shorter than required')
        tags = self.call('get-bucket-tagging').get('TagSet', [])
        require(any(t.get('Key') == 'SafeAIPurpose' and t.get('Value') == 'audit-checkpoint' for t in tags),
                'dedicated audit checkpoint bucket required')

    def key(self, kind, sequence):
        return f'audit-checkpoints/{self.trust.dataset}/{kind}/{sequence:012d}'

    def version(self, kind, sequence):
        key = self.key(kind, sequence)
        result = self.call('list-object-versions', '--prefix', key, '--max-keys', '2', '--no-paginate')
        require(result.get('IsTruncated') is False and not result.get('DeleteMarkers'), 'ambiguous archive versions or delete marker')
        versions = result.get('Versions', [])
        if not versions:
            return None
        require(len(versions) == 1 and versions[0].get('Key') == key and versions[0].get('IsLatest') is True,
                'archive object replaced or forked')
        return versions[0]

    def validate_scope(self, pin):
        validate_pin(pin, self.trust)
        require(pin['bucket'] == self.bucket and pin['account'] == self.account, 'wrong archive bucket/owner')

    def read(self, pin):
        self.validate_scope(pin)
        version = self.version(pin['kind'], pin['sequence'])
        require(version is not None and version.get('VersionId') == pin['version_id']
                and date(version.get('LastModified')) == date(pin['stored_at']), 'pinned version missing or superseded')
        key = self.key(pin['kind'], pin['sequence'])
        options = ('--key', key, '--version-id', pin['version_id'])
        retention = self.call('get-object-retention', *options).get('Retention', {})
        require(retention.get('Mode') == 'COMPLIANCE' and date(retention.get('RetainUntilDate')) >= date(pin['retain_until'])
                and date(pin['retain_until']) > now()
                and date(pin['retain_until']) >= date(pin['stored_at']) + timedelta(days=self.days),
                'pinned COMPLIANCE retention missing, reduced or expired')
        head = self.call('head-object', *options)
        size = head.get('ContentLength')
        require(head.get('VersionId') == pin['version_id'] and head.get('ServerSideEncryption') == 'AES256'
                and type(size) is int and 0 < size <= MAX_OBJECT, 'invalid archived object metadata')
        with tempfile.TemporaryDirectory(prefix='safeai-archive-read-') as directory:
            path = Path(directory) / 'record'
            downloaded = self.call('get-object', *options, str(path))
            require(downloaded.get('VersionId') == pin['version_id'], 'S3 returned wrong version')
            record = load_envelope(path, self.trust)
            require(len(record.data) == size, 'S3 content length mismatch')
        record.matches(pin, self.trust)
        require(self.version(pin['kind'], pin['sequence']) == version, 'archive changed during read')
        return record

    def store(self, record):
        require(self.version(record.kind, record.sequence) is None, 'archive slot already exists; never overwrite')
        until = (now() + timedelta(days=self.days, minutes=5)).replace(microsecond=0)
        with tempfile.TemporaryDirectory(prefix='safeai-archive-write-') as directory:
            path = Path(directory) / 'record'
            path.write_bytes(record.data)
            response = self.call('put-object', '--key', self.key(record.kind, record.sequence), '--body', str(path),
                                 '--if-none-match', '*', '--object-lock-mode', 'COMPLIANCE',
                                 '--object-lock-retain-until-date', stamp(until), '--server-side-encryption', 'AES256',
                                 '--checksum-sha256', b64(bytes.fromhex(record.sha256)))
        return self.confirm(record, response.get('VersionId'), until)

    def confirm(self, record, version_id, minimum_until):
        version = self.version(record.kind, record.sequence)
        require(version is not None and version.get('VersionId') == version_id, 'S3 version not confirmed')
        retain = self.call('get-object-retention', '--key', self.key(record.kind, record.sequence),
                           '--version-id', version_id).get('Retention', {})
        require(retain.get('Mode') == 'COMPLIANCE' and date(retain.get('RetainUntilDate')) >= minimum_until,
                'S3 retention below requested deadline')
        pin = dict(dataset=self.trust.dataset, kind=record.kind, sequence=record.sequence, bucket=self.bucket,
                   account=self.account, sha256=record.sha256, version_id=version_id,
                   retain_until=stamp(date(retain['RetainUntilDate'])), stored_at=stamp(date(version['LastModified'])))
        self.read(pin)
        return pin


def verify_chain(storage, tip, trust):
    # Walk backwards to obtain pinned pointers; then validate every transition forwards.
    chain = []
    pin = tip
    while pin is not None:
        require(len(chain) <= MAX_STEPS, 'archive history exceeds verification bound')
        record = storage.read(pin)
        require(record.kind == 'bundle', 'non-checkpoint in archive chain')
        chain.append(pin)
        pin = record.fields['previous']
    previous = previous_pin = None
    tombstones = set()
    for pin in reversed(chain):
        record = storage.read(pin)
        require(not any(event in tombstones for event, _ in record.manifest.events()), 'deleted evidence reappeared')
        permit_pin = record.fields['permit']
        permit = storage.read(permit_pin) if permit_pin is not None else None
        tombstones.update(validate_link(record, previous, previous_pin, trust, permit, permit_pin))
        require(len(tombstones) <= 1000000, 'retention history exceeds verification bound')
        previous, previous_pin = record, pin
    return previous, tombstones


def validate_candidate(storage, record, previous_pin, trust):
    require(record.manifest.captured_at <= now(), 'capture timestamp is in the future')
    previous, tombstones = verify_chain(storage, previous_pin, trust) if previous_pin else (None, set())
    if record.kind == 'permit':
        require(previous is not None, 'permit needs archived checkpoint')
        require(date(record.fields['issued_at']) <= now() < date(record.fields['expires_at']), 'permit expired or not yet issued')
        validate_permit(record, previous, previous_pin, trust)
        require(not any(event in tombstones for event, _ in record.manifest.events()), 'deleted evidence reappeared')
        require(storage.version('bundle', record.sequence) is None, 'checkpoint already published for permit slot')
    else:
        require(not any(event in tombstones for event, _ in record.manifest.events()), 'deleted evidence reappeared')
        permit_pin = record.fields['permit']
        permit = storage.read(permit_pin) if permit_pin else None
        if permit_pin is None:
            require(storage.version('permit', record.sequence) is None, 'unresolved retention authorization exists')
        validate_link(record, previous, previous_pin, trust, permit, permit_pin)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dataset', required=True)
    parser.add_argument('--root-key-sha256', required=True)
    parser.add_argument('--retention-key-sha256', required=True)
    parser.add_argument('--genesis-manifest-sha256', required=True)
    commands = parser.add_subparsers(dest='command', required=True)
    seal = commands.add_parser('seal')
    for name in ('manifest', 'private-key', 'output'):
        seal.add_argument('--' + name, required=True, type=Path)
    for name in ('previous', 'previous-pin', 'old-private-key', 'permit', 'permit-pin', 'genesis-signature'):
        seal.add_argument('--' + name, type=Path)
    seal.add_argument('--genesis-sha256')
    permit = commands.add_parser('permit')
    for name in ('manifest', 'private-key', 'previous', 'previous-pin', 'items', 'output'):
        permit.add_argument('--' + name, type=Path, required=True)
    permit.add_argument('--policy-evidence-sha256', required=True)
    permit.add_argument('--expires-at', required=True)
    for name in ('publish', 'recover', 'verify'):
        action = commands.add_parser(name)
        action.add_argument('--bucket', required=True)
        action.add_argument('--account', required=True)
        action.add_argument('--retention-days', type=int, default=1095)
        if name in ('publish', 'recover'):
            action.add_argument('--record', type=Path, required=True)
            action.add_argument('--previous-pin', type=Path)
            action.add_argument('--output-pin', type=Path, required=True)
            if name == 'recover':
                action.add_argument('--version-id', required=True)
        else:
            action.add_argument('--pin', type=Path, required=True)
            action.add_argument('--current', type=Path, required=True)
            action.add_argument('--output', type=Path)
    args = parser.parse_args()
    trust = Trust(args.dataset, args.root_key_sha256, args.retention_key_sha256, args.genesis_manifest_sha256)
    if args.command in ('seal', 'permit'):
        manifest = checkpoint.read_manifest(args.manifest)
        require(bool(args.previous) == bool(args.previous_pin), 'previous bundle and receipt required together')
        previous = load_envelope(args.previous, trust) if args.previous else None
        pin = read_pin(args.previous_pin, trust, 'bundle') if args.previous_pin else None
        fields = {'sequence': pin['sequence'] + 1 if pin else 0, 'previous': pin}
        if args.command == 'permit':
            items = json.loads(read_bounded(args.items, MAX_HEADER))
            fields.update(kind='permit', items=items, issued_at=stamp(now()), expires_at=stamp(date(args.expires_at)),
                          policy_evidence_sha256=args.policy_evidence_sha256)
            record = create_envelope(fields, manifest, args.private_key, trust)
            validate_permit(record, previous, pin, trust)
        else:
            require(bool(args.permit) == bool(args.permit_pin), 'permit and receipt required together')
            approval = load_envelope(args.permit, trust) if args.permit else None
            approval_pin = read_pin(args.permit_pin, trust, 'permit') if args.permit_pin else None
            if previous is None:
                require(args.genesis_signature is not None and args.genesis_sha256 == manifest.sha256
                        and not args.old_private_key and not approval, 'independently pinned stage 7.5B.4.2.1 genesis required')
                require(sha(private_der(args.private_key)) == trust.root, 'wrong genesis signing key')
                with tempfile.TemporaryDirectory(prefix='safeai-genesis-') as directory:
                    public = Path(directory) / 'public.pem'
                    public.write_bytes(checkpoint.openssl('pkey', '-in', str(args.private_key), '-pubout'))
                    checkpoint.signature_operation(manifest, public, read_bounded(args.genesis_signature, 64))
            else:
                require(not args.genesis_signature and not args.genesis_sha256, 'unexpected genesis arguments')
                if args.old_private_key:
                    require(private_der(args.old_private_key) == previous.key, 'wrong retiring key')
            fields.update(kind='bundle', permit=approval_pin)
            record = create_envelope(fields, manifest, args.private_key, trust, args.old_private_key)
            validate_link(record, previous, pin, trust, approval, approval_pin)
        write_file(args.output, record.data)
        print('SEALED (not archived): ' + record.sha256)
        return
    storage = S3(args.bucket, args.account, trust, args.retention_days)
    storage.preflight()
    if args.command in ('publish', 'recover'):
        require(not args.output_pin.exists(), 'receipt output exists')
        pin = read_pin(args.previous_pin, trust, 'bundle') if args.previous_pin else None
        record = load_envelope(args.record, trust)
        validate_candidate(storage, record, pin, trust)
        receipt = (storage.store(record) if args.command == 'publish' else storage.confirm(
            record, args.version_id, now() + timedelta(days=storage.days)))
        write_file(args.output_pin, line(receipt))
        print('ARCHIVED: ' + receipt['sha256'] + '; independently accept receipt only after verification')
    else:
        pin = read_pin(args.pin, trust, 'bundle')
        last, tombstones = verify_chain(storage, pin, trust)
        require(storage.version('bundle', pin['sequence'] + 1) is None, 'newer unaccepted checkpoint exists')
        current = checkpoint.read_manifest(args.current)
        require(date(pin['stored_at']) <= current.captured_at <= now(), 'current capture is not fresh')
        require(not any(event in tombstones for event, _ in current.events()), 'deleted evidence reappeared')
        require(not differences(last.manifest, current), 'anchored evidence missing')
        if args.output:
            write_file(args.output, last.data)
        print('PASS: archived chain and current anchored evidence; new events still unanchored')


if __name__ == '__main__':
    try:
        main()
    except (Invalid, OSError, ValueError, KeyError, TypeError, AttributeError, subprocess.SubprocessError) as error:
        print('BLOCKED: archive operation failed; no success or deletion authorized', file=sys.stderr)
        # Only our fixed diagnostic messages, never raw provider responses/PII.
        if isinstance(error, Invalid):
            print(str(error), file=sys.stderr)
        sys.exit(1)
