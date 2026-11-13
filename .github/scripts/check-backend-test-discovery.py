#!/usr/bin/env python3
"""Контроль запуска каждого локального *IT и инвентаризация функциональных модулей."""
from __future__ import annotations
import argparse
import json
import re
import sys
from collections import defaultdict
from pathlib import Path
from xml.etree import ElementTree as ET

MODULES = ('common','auth','user','organization','ratelimit','usage','audit','model','ai','chat','knowledge','identity')


def verify(repo: Path) -> dict:
    src = repo / 'backend/src/test/java'
    reports_dir = repo / 'backend/target/surefire-reports'
    files = sorted(reports_dir.glob('TEST-*.xml'))
    if not src.is_dir() or not files:
        raise ValueError('Нет тестовых исходников либо XML-отчётов Surefire')

    observed: dict[str, list[tuple[str, bool]]] = defaultdict(list)
    for xml in files:
        root = ET.parse(xml).getroot()
        for testcase in root.iter('testcase'):
            classname = testcase.attrib.get('classname', '')
            if classname:
                observed[classname].append((testcase.attrib.get('name', ''), testcase.find('skipped') is not None))

    integration, external = [], []
    source_tests = defaultdict(int)
    for java in sorted(src.rglob('*.java')):
        content = java.read_text(encoding='utf-8')
        package = re.search(r'^\s*package\s+([\w.]+)\s*;', content, re.M)
        if not package:
            continue
        classname = f'{package.group(1)}.{java.stem}'
        segments = package.group(1).split('.')
        if len(segments) >= 4 and segments[:3] == ['ru','safeai','gateway']:
            module = segments[3]
            if '/ratelimit/external/' not in java.as_posix() and (java.stem.endswith(('Test','Tests','TestCase','IT')) or java.stem.startswith('Test')):
                source_tests[module] += 1
        if java.stem.endswith('IT'):
            if '/ratelimit/external/' in java.as_posix():
                external.append(classname)
            else:
                integration.append(classname)

    if not integration:
        raise ValueError('Ни один локальный *IT не найден: структура тестов изменилась')
    missing = [name for name in integration if name not in observed]
    skipped = [name for name in integration if name in observed and all(skip for _,skip in observed[name])]
    if missing or skipped:
        raise ValueError(f'Не запущены обязательные локальные *IT: missing={missing}, skipped={skipped}')

    expected_external = {
        'ru.safeai.gateway.ratelimit.external.RedisClusterRateLimitIT',
        'ru.safeai.gateway.ratelimit.external.RedisSentinelFailoverIT',
    }
    if set(external) != expected_external:
        raise ValueError(f'Изменился набор внешних Redis *IT: {sorted(external)}. Обнови защищённый workflow и проверку.')
    accidentally_run = [name for name in external if name in observed]
    if accidentally_run:
        raise ValueError(f'Внешние Redis-тесты попали в обычный CI: {accidentally_run}')

    report_modules = defaultdict(set)
    for classname in observed:
        parts = classname.split('.')
        if len(parts) >= 5 and parts[:3] == ['ru','safeai','gateway']:
            report_modules[parts[3]].add(classname)
    result = {'local_it': integration, 'external_it': external,
              'modules': {m: {'test_sources':source_tests[m], 'executed_classes':len(report_modules[m])} for m in MODULES}}
    print('Локальные интеграционные *IT:',len(integration),'из',len(integration),'обнаружены в отчётах')
    print('Внешние Redis *IT (отдельный защищённый запуск):',len(external))
    for module, stats in result['modules'].items():
        print(f'{module:15} тестовых исходников={stats["test_sources"]:3} классов в отчётах={stats["executed_classes"]:3}')
    uncovered = [m for m in MODULES if source_tests[m] and not report_modules[m]]
    if uncovered:
        raise ValueError(f'Есть тестовые исходники, но не выполнен ни один тест модуля: {uncovered}')
    return result


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument('--repo',type=Path,default=Path('.'))
    args=p.parse_args()
    try:
        result=verify(args.repo.resolve())
    except (ValueError, OSError, ET.ParseError) as error:
        print(f'CI TEST DISCOVERY FAILED: {error}',file=sys.stderr)
        return 1
    out=args.repo.resolve()/'backend/target/ci-test-inventory.json'
    out.parent.mkdir(parents=True,exist_ok=True)
    out.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('CI TEST DISCOVERY OK')
    return 0

if __name__=='__main__':
    raise SystemExit(main())
