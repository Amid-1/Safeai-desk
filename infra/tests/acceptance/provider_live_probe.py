#!/usr/bin/env python3
"""Минимальные live-контрактные проверки OpenAI/Anthropic без пользовательских данных.

Проверяет реальные upstream API, но НЕ доказывает маршрутизацию SafeAI Desk.
Один короткий запрос каждому провайдеру, без ретраев и вывода тела ответа.
"""
from __future__ import annotations
import json
import os
import sys
import urllib.error
import urllib.request


def call(url: str, headers: dict[str,str], payload: dict) -> dict:
    req = urllib.request.Request(url,
        data=json.dumps(payload).encode(),headers={**headers,'Content-Type':'application/json'},method='POST')
    try:
        with urllib.request.urlopen(req,timeout=25) as result:
            if result.status!=200:
                raise RuntimeError(f'HTTP {result.status}')
            return json.load(result)
    except urllib.error.HTTPError as exc:
        raise RuntimeError(f'HTTP {exc.code} from provider') from None


def require(name: str) -> str:
    value=os.getenv(name,'')
    if not value:
        raise RuntimeError(f'Missing required staging secret/variable: {name}')
    return value


def main() -> None:
    if os.getenv('SAFEAI_PROVIDER_APPROVAL') != 'I_ACCEPT_PAID_STAGING_REQUESTS':
        raise RuntimeError('Live provider smoke not approved')
    openai=call('https://api.openai.com/v1/responses',
        {'Authorization':'Bearer '+require('SAFEAI_TEST_OPENAI_KEY')},
        {'model':require('SAFEAI_TEST_OPENAI_MODEL'), 'input':'Reply with OK.', 'max_output_tokens':16,'store':False})
    if not isinstance(openai.get('id'),str) or not isinstance(openai.get('output'),list):
        raise RuntimeError('Invalid OpenAI Responses API shape')
    print('OpenAI: PASS, short HTTPS Responses API request')
    anthropic=call('https://api.anthropic.com/v1/messages',
        {'x-api-key':require('SAFEAI_TEST_ANTHROPIC_KEY'),'anthropic-version':'2023-06-01'},
        {'model':require('SAFEAI_TEST_ANTHROPIC_MODEL'),'max_tokens':16,
         'messages':[{'role':'user','content':'Reply with OK.'}]})
    if not isinstance(anthropic.get('id'),str) or not isinstance(anthropic.get('content'),list):
        raise RuntimeError('Invalid Anthropic Messages API shape')
    print('Anthropic: PASS, short HTTPS Messages API request')


if __name__=='__main__':
    try:
        main()
    except Exception as exc:
        print(f'LIVE PROVIDER PROBE FAILED: {exc}',file=sys.stderr)
        raise SystemExit(1)
