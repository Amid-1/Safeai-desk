#!/usr/bin/env python3
"""Fail-closed проверка двух внешних Redis-тестов после Failsafe verify."""
from pathlib import Path
from xml.etree import ElementTree as ET
import sys

EXPECTED = {
    'ru.safeai.gateway.ratelimit.external.RedisClusterRateLimitIT',
    'ru.safeai.gateway.ratelimit.external.RedisSentinelFailoverIT',
}

def main(root: Path) -> int:
    directory=root/'backend/target/failsafe-reports'
    reports=list(directory.glob('TEST-*.xml'))
    if not reports:
        print('Нет XML-отчётов Failsafe',file=sys.stderr);return 1
    executed=set()
    for file in reports:
        for test in ET.parse(file).getroot().iter('testcase'):
            cls=test.get('classname','')
            if cls in EXPECTED:
                if any(test.find(tag) is not None for tag in ('skipped','error','failure')):
                    print('Внешний Redis IT не прошёл:',cls,test.get('name'),file=sys.stderr);return 1
                executed.add(cls)
    if executed!=EXPECTED:
        print('Отсутствуют результаты:',sorted(EXPECTED-executed),file=sys.stderr);return 1
    print('Redis Cluster и Sentinel: оба внешних теста выполнены без ошибок и пропусков')
    return 0

if __name__=='__main__':
    raise SystemExit(main(Path(sys.argv[1] if len(sys.argv)>1 else '.').resolve()))
