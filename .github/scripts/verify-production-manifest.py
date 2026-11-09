#!/usr/bin/env python3
"""Verify one successful immutable publish run and its exact image manifest."""
import argparse
import json
import os
from pathlib import Path
import re

IMAGE = re.compile(r'^ghcr\.io/([a-z0-9.-]+)/safeai-(backend|nginx|postgres)@sha256:[0-9a-f]{64}$')
SHA = re.compile(r'^[0-9a-f]{40}$')
RUN = re.compile(r'^[1-9][0-9]{0,19}$')
EXPECTED = {'release_sha', 'publish_run_id', 'backend_image', 'nginx_image', 'postgres_image'}


def verify(run: dict, manifest: dict, sha: str, run_id: str, repository: str) -> dict:
    if not SHA.fullmatch(sha) or not RUN.fullmatch(run_id):
        raise ValueError('Invalid release SHA or workflow run ID')
    if run.get('id') != int(run_id):
        raise ValueError('Wrong publisher run ID')
    if run.get('head_sha') != sha or run.get('head_branch') != 'main':
        raise ValueError('Publisher must use this main commit')
    if run.get('status') != 'completed' or run.get('conclusion') != 'success':
        raise ValueError('Publisher is not completed successfully')
    if run.get('event') != 'workflow_dispatch':
        raise ValueError('Publisher was not manually dispatched')
    expected_path = f'{repository}/.github/workflows/production-release.yml'
    actual_path = run.get('path', '').lstrip('/')
    if actual_path not in ('.github/workflows/production-release.yml', expected_path):
        raise ValueError('Unrecognized publisher workflow')
    if set(manifest) != EXPECTED:
        raise ValueError('Manifest has missing or unexpected keys')
    if manifest['release_sha'] != sha or str(manifest['publish_run_id']) != run_id:
        raise ValueError('Manifest release SHA / run ID mismatch')
    expected_owner = repository.partition('/')[0].lower()
    for kind in ('backend', 'nginx', 'postgres'):
        value = manifest[f'{kind}_image']
        match = IMAGE.fullmatch(value) if isinstance(value, str) else None
        if not match or match.group(1) != expected_owner or match.group(2) != kind:
            raise ValueError(f'Invalid {kind} image digest')
    return {k: manifest[k] for k in ('backend_image', 'nginx_image', 'postgres_image')}


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument('--run-json', required=True, type=Path)
    p.add_argument('--manifest-json', required=True, type=Path)
    p.add_argument('--sha', required=True)
    p.add_argument('--run-id', required=True)
    p.add_argument('--repository', required=True)
    args = p.parse_args()
    run = json.loads(args.run_json.read_text(encoding='utf-8'))
    manifest = json.loads(args.manifest_json.read_text(encoding='utf-8'))
    result = verify(run, manifest, args.sha, args.run_id, args.repository)
    output = os.getenv('GITHUB_OUTPUT')
    if not output:
        raise SystemExit('GITHUB_OUTPUT is required in protected deploy job')
    with Path(output).open('a', encoding='utf-8') as f:
        for key, value in result.items():
            f.write(f'{key}={value}\n')
    print('PUBLISH MANIFEST VERIFIED: exact run, main SHA, 3 immutable image digests')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, TypeError, json.JSONDecodeError) as exc:
        raise SystemExit(f'BLOCKED: {exc}') from exc
