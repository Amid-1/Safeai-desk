#!/usr/bin/env python3
"""Доказательство реального S3 Object Lock + Legal Hold на ОТДЕЛЬНОМ staging-bucket.

Создаёт единственную короткоживущую COMPLIANCE-версию, которую нельзя удалить
до retain-until; НЕ запускайте на production. Отчёт не содержит секретов.
"""
from __future__ import annotations
import datetime as dt
import json
import os
import subprocess
import sys
import tempfile
import uuid
from pathlib import Path


def aws(*args: str, allowed_failure=False) -> dict | tuple[bool,str]:
    proc = subprocess.run(['aws', *args, '--output', 'json'], text=True, capture_output=True)
    if allowed_failure:
        return proc.returncode == 0, (proc.stderr or '')[:300]
    if proc.returncode:
        raise RuntimeError(f'AWS CLI failed for {args[0]} {args[1] if len(args)>1 else ""}: {proc.stderr[:400]}')
    return json.loads(proc.stdout) if proc.stdout.strip() else {}


def main() -> None:
    env = os.environ
    if env.get('SAFEAI_WORM_APPROVAL') != 'I_ACCEPT_IMMUTABLE_STAGING_OBJECTS':
        raise RuntimeError('Явное подтверждение необратимой COMPLIANCE retention не задано')
    bucket = env.get('SAFEAI_TEST_S3_BUCKET','')
    expected_account = env.get('SAFEAI_TEST_AWS_ACCOUNT_ID','')
    if not bucket or not expected_account.isdigit() or len(expected_account)!=12:
        raise RuntimeError('Нужны staging-bucket и 12-значный идентификатор AWS-аккаунта')
    caller=aws('sts','get-caller-identity')
    if caller['Account'] != expected_account:
        raise RuntimeError('Неверный AWS-аккаунт. Отказ без записи объектов.')
    tags=aws('s3api','get-bucket-tagging','--bucket',bucket).get('TagSet',[])
    if not any(t.get('Key')=='SafeAITestOnly' and t.get('Value')=='true' for t in tags):
        raise RuntimeError('Bucket не имеет обязательного тега SafeAITestOnly=true')
    config=aws('s3api','get-object-lock-configuration','--bucket',bucket)
    if config.get('ObjectLockConfiguration',{}).get('ObjectLockEnabled') != 'Enabled':
        raise RuntimeError('Object Lock не включён для staging bucket')
    prefix='safeai-acceptance/'+uuid.uuid4().hex+'/'
    deadline=(dt.datetime.now(dt.timezone.utc)+dt.timedelta(minutes=15)).isoformat().replace('+00:00','Z')
    with tempfile.NamedTemporaryFile(mode='wb',delete=False) as handle:
        handle.write(('safeai-worm-test '+prefix).encode())
        body=handle.name
    try:
        def put(key: str, *extra: str) -> str:
            response=aws('s3api','put-object','--bucket',bucket,'--key',prefix+key,'--body',body,*extra)
            version=response.get('VersionId')
            if not version or version=='null':
                raise RuntimeError('S3 не возвратил VersionId: тест не может доказывать WORM')
            return version
        def delete(key: str, version: str):
            return aws('s3api','delete-object','--bucket',bucket,'--key',prefix+key,'--version-id',version,allowed_failure=True)
        control=put('control.txt')
        ok,err=delete('control.txt',control)
        if not ok:
            raise RuntimeError('Контрольное удаление НЕ прошло: IAM уже запрещает удаление; нельзя доказать блокировку Object Lock')
        compliance=put('compliance.txt','--object-lock-mode','COMPLIANCE','--object-lock-retain-until-date',deadline)
        retention=aws('s3api','get-object-retention','--bucket',bucket,'--key',prefix+'compliance.txt','--version-id',compliance)
        mode=retention.get('Retention',{}).get('Mode')
        if mode!='COMPLIANCE':
            raise RuntimeError(f'Верификация COMPLIANCE не прошла: {mode!r}')
        permitted,_=delete('compliance.txt',compliance)
        if permitted:
            raise RuntimeError('ОПАСНО: S3 удалил COMPLIANCE version до конца retention')
        legal=put('legal-hold.txt','--object-lock-legal-hold-status','ON')
        hold=aws('s3api','get-object-legal-hold','--bucket',bucket,'--key',prefix+'legal-hold.txt','--version-id',legal)
        if hold.get('LegalHold',{}).get('Status')!='ON':
            raise RuntimeError('Legal Hold не установлен')
        permitted,_=delete('legal-hold.txt',legal)
        if permitted:
            raise RuntimeError('ОПАСНО: S3 удалил версию с включённым Legal Hold')
        # Clean up the hold-only object. COMPLIANCE object must remain until expiration.
        aws('s3api','put-object-legal-hold','--bucket',bucket,'--key',prefix+'legal-hold.txt',
            '--version-id',legal,'--legal-hold','Status=OFF')
        ok,_=delete('legal-hold.txt',legal)
        if not ok:
            raise RuntimeError('Не удалось удалить контрольный legal-hold объект после снятия hold')
        print(json.dumps({'result':'PASS','bucket':bucket,'prefix':prefix,'retention_version':compliance,
                          'retain_until':deadline,'manual_cleanup_required':True},ensure_ascii=False))
    finally:
        Path(body).unlink(missing_ok=True)


if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        print(f'S3 WORM PROBE FAILED: {exc}',file=sys.stderr)
        raise SystemExit(1)
