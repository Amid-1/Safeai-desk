#SafeAI-Desk/.github/scripts/check-infra-security-policy.py
#!/usr/bin/env python3
"""Fail-closed policy: Noble build parity, supply-chain protection and release gate.

Pure read-only checks. No Docker, registry, database, secrets or deploy access.
"""
from pathlib import Path
import re
import sys

UBUNTU = 'ubuntu:24.04@sha256:fe81560990fae86dc4743202b2cb4d3df98d0bcf949173cecef15ce1127ef852'
GO = 'golang:1.27.4-trixie@sha256:f18c3d73a184fb0582828510722e723b63a93f7188d11dd223d9be6f6880e65d'
ENTRYPOINT = (
    'pgvector/pgvector:0.8.7-pg16-trixie@sha256:'
    '9cf8b511f0e9a2c209b61430fc139f1ab9c75edbb31fb1751db6617b246a897a'
)
VERSION = 'v0.76.2'


def must(ok: bool, message: str):
    if not ok:
        raise AssertionError(message)


def check_trivy(name: str, text: str, min_critical: int, min_high: int):
    blocks = re.split(r'(?m)^\s+- name: ', text)
    scans = [b for b in blocks if 'uses: aquasecurity/trivy-action@v0.36.0' in b]
    must(bool(scans), name + ': отсутствуют Trivy checks')
    for block in scans:
        must(f'version: {VERSION}' in block, name + ': версия Trivy изменена')
    critical = [b for b in scans if re.search(r'(?m)^\s+severity: CRITICAL\s*$', b)]
    high = [b for b in scans if re.search(r'(?m)^\s+severity: HIGH,CRITICAL\s*$', b)]
    must(len(critical) >= min_critical, name + ': уменьшено число CRITICAL gates')
    must(len(high) >= min_high, name + ': уменьшено число HIGH/CRITICAL gates')
    for block in critical:
        must('ignore-unfixed: false' in block, name + ': скрыты unfixed CRITICAL')
        must("exit-code: '1'" in block, name + ': CRITICAL перестал блокировать')
    for block in high:
        must('ignore-unfixed: true' in block, name + ': режим исправимых HIGH/CRITICAL изменён')
    blocking_high = [b for b in high if "exit-code: '1'" in b]
    must(len(blocking_high) >= min_high,
         name + ': уменьшено число блокирующих HIGH/CRITICAL gates')


def assert_policy(repo: Path) -> None:
    must(__debug__, 'Запрещено запускать policy с Python -O')
    read = lambda p: (repo / p).read_text(encoding='utf-8')
    lock = read('infra/postgres/base-image.txt').strip()
    must(lock == UBUNTU, 'Ubuntu base digest не совпадает с утверждённым')
    pg = read('infra/postgres/Dockerfile')
    candidate = read('infra/postgres/noble-candidate/Dockerfile')
    for field in (f'ARG UBUNTU_IMAGE={UBUNTU}',
                  f'ARG GOSU_BUILDER_IMAGE={GO}',
                  'export GOTOOLCHAIN=local;',
                  'test "$(go env GOVERSION)" = go1.27.4;',
                  'test "$(go version /out/gosu)" = \'/out/gosu: go1.27.4\';',
                  f'ARG ENTRYPOINT_SOURCE_IMAGE={ENTRYPOINT}',
                  '29b939aa5bd4dc588af765b8248f6e39150d2565',
                  'f37c13f68b57d2c3472b2214fbcff699d6d34876',
                  'POSTGRES_APT_VERSION=16.15-0ubuntu0.24.04.1',
                  'dpkg --compare-versions "$xml_ver" ge',
                  'COPY --from=gosu-build',
                  'COPY --from=vector-build',
                  'COPY --from=upstream-entrypoint',
                  'infra/postgres/init/10-create-app-roles.sh',
                  'VOLUME /var/lib/postgresql/data',
                  'ENTRYPOINT ["docker-entrypoint.sh"]'):
        must(field in pg and field in candidate, 'PG Dockerfile/candidate parity: ' + field)
    must('candidate-only-no-production' not in pg, 'Основной Dockerfile помечен как candidate-only')
    must('io.safeai.release-status="release-controlled"' in pg,
         'Нет метки контроля production release')
    must('ARG POSTGRES_BASE_IMAGE=pgvector/pgvector' not in pg,
         'Основной Dockerfile продолжает использовать Debian pgvector')
    must('FROM ${UBUNTU_IMAGE} AS postgres-core' in pg,
         'Основной PostgreSQL не собирается из Ubuntu')

    docker = read('.github/workflows/docker-ci.yml')
    prod = read('.github/workflows/production-release.yml')
    build = read('infra/scripts/build-prod.sh')
    security = read('.github/workflows/security-ci.yml')
    policy_workflow = read('.github/workflows/ci-policy.yml')
    performance = read('.github/workflows/performance-ci.yml')
    for trigger in ('workflow_dispatch', 'schedule', 'workflow_call'):
        must(re.search(r'(?m)^  ' + trigger + r':\s*$', performance),
             'Performance CI: отсутствует ' + trigger)
    for field in ('-Dsafeai.erasure.load-tests=true',
                  '-Dsafeai.usage.performance=true',
                  'ChatErasureBatchBoundaryV86IntegrationTest,UsageExplainAnalyzePerformanceIntegrationTest,UsagePerformanceIntegrationTest,UsagePhysicalExplainPerformanceIntegrationTest',
                  'run: python3 .github/scripts/check-performance-reports.py backend/target/surefire-reports',
                  'check rules rules/safeai-usage.yml', 'test rules tests/usage.test.yml'):
        must(field in performance, 'Performance CI: отсутствует ' + field)
    must('continue-on-error:' not in performance, 'Performance CI: ошибки не блокируют релиз')
    release_gate = re.search(r'(?ms)^  performance:\n(.*?)(?=^  \w[\w-]*:|\Z)', prod)
    must(bool(release_gate), 'Production release: отсутствует performance gate')
    must('    needs: preflight\n' in release_gate[1]
         and '    uses: ./.github/workflows/performance-ci.yml\n' in release_gate[1]
         and 'continue-on-error:' not in release_gate[1],
         'Production release: изменён обязательный Performance CI')
    assurance_gate = re.search(r'(?ms)^  provider-data-assurance:\n(.*?)(?=^  \w[\w-]*:|\Z)', prod)
    must(bool(assurance_gate), 'Production release: отсутствует Provider Data Assurance')
    must('    needs: preflight\n' in assurance_gate[1]
         and '    uses: ./.github/workflows/provider-data-assurance.yml\n' in assurance_gate[1]
         and 'continue-on-error:' not in assurance_gate[1],
         'Production release: изменён обязательный Provider Data Assurance')
    publish = re.search(r'(?ms)^  publish:\n(.*?)(?=^  \w[\w-]*:|\Z)', prod)
    must(bool(publish), 'Production release: отсутствует publish')
    needs = re.findall(r'(?m)^    needs: \[([^\]\n]+)\][ \t]*$', publish[1])
    must(len(needs) == 1, 'Production release: неверный список publish.needs')
    dependencies = [item.strip() for item in needs[0].split(',')]
    must(len(dependencies) == len(set(dependencies))
         and all(re.fullmatch(r'[A-Za-z_][A-Za-z0-9_-]*', item) for item in dependencies),
         'Production release: неверные зависимости publish')
    must({'preflight', 'performance', 'provider-data-assurance'} <= set(dependencies),
         'Production release: publish не ждёт все обязательные шлюзы')
    must(re.findall(r'(?m)^    if: (.*)$', publish[1]) == ["github.ref == 'refs/heads/main'"]
         and not re.search(r'(?m)^    continue-on-error:', publish[1]),
         'Production release: изменено обязательное условие publish')
    must('test_performance_reports.py' in policy_workflow,
         'CI policy: отсутствуют тесты Performance CI')
    assert 'infra/postgres/Dockerfile' in docker and 'infra/postgres/noble-candidate/Dockerfile' not in docker
    assert 'cat infra/postgres/base-image.txt' in docker
    assert 'UBUNTU_IMAGE=$base_image' in docker
    assert 'bash infra/scripts/test-postgres-bootstrap.sh safeai-postgres:ci' in docker
    assert 'bash infra/scripts/test-runtime-acl.sh safeai-postgres:ci safeai-backend:ci' in docker
    assert 'bash infra/postgres/noble-candidate/xml-hnsw-smoke.sh safeai-postgres:ci' in docker
    assert 'docker buildx build --platform linux/amd64' in docker
    assert 'cat infra/postgres/base-image.txt' in prod
    assert 'ubuntu:24[.]04@sha256:' in prod
    assert 'pin_image pgvector/pgvector:pg16' not in prod
    deploy = read('.github/workflows/production-deploy.yml')
    assert 'SAFEAI_NOBLE_RESTORE_APPROVED_IMAGE' in deploy
    assert '"$APPROVED_IMAGE" == "$POSTGRES_IMAGE"' in deploy
    assert 'verify-production-manifest.py' in deploy
    assert 'gh attestation verify' in deploy
    assert 'inputs.publish_run_id' in deploy
    assert 'safeai-published-images-' in prod
    assert '  deploy:' not in prod
    assert 'SAFEAI_POSTGRES_BUILD_TAG' in prod
    assert 'build-prod.sh "$build_env"' in prod
    assert 'bash infra/scripts/test-runtime-acl.sh' in prod
    assert 'safeai-$image.id' in prod
    assert prod.index('bash infra/scripts/test-runtime-acl.sh') < prod.index('docker push ')
    assert 'attestations: write' in prod
    assert 'artifact-metadata: write' not in prod
    assert prod.count('create-storage-record: false') == 6
    assert prod.count('push-to-registry: true') == 6
    assert 'gh attestation verify' in prod
    assert 'postgres.sbom.cdx.json' in prod
    assert 'SAFEAI_REQUIRE_IMMUTABLE_IMAGES=true' in prod
    assert 'test "$POSTGRES_BASE_IMAGE"' not in build  # exact lock equality is checked below
    assert 'infra/postgres/base-image.txt' in build
    assert '"$POSTGRES_BASE_IMAGE" == "$expected_postgres_base"' in build
    assert '--file "$repo_dir/infra/postgres/Dockerfile"' in build
    assert '"UBUNTU_IMAGE=$POSTGRES_BASE_IMAGE"' in build
    assert 'git commit' not in build
    assert 'docker compose down -v' not in build
    assert 'name: safeai_postgres_data' in read('infra/docker-compose.yml')
    assert 'docker restart "$postgres_container"' in read('infra/scripts/test-postgres-bootstrap.sh')
    assert 'python3 .github/scripts/check-infra-security-policy.py' in policy_workflow
    assert 'test_infra_security_policy.py' in policy_workflow
    check_trivy('docker-ci.yml', docker, min_critical=3, min_high=3)
    check_trivy('production-release.yml', prod, min_critical=3, min_high=3)
    check_trivy('security-ci.yml', security, min_critical=1, min_high=1)
    names = ('.trivyignore', '.trivyignore.yaml', '.trivyignore.yml')
    ignored_files = [repo / filename for filename in names]
    for folder in (repo / '.github', repo / 'infra'):
        ignored_files.extend(p for p in folder.rglob('*') if p.name in names)
    for path in ignored_files:
        if path.is_file():
            must('CVE-2026-6653' not in path.read_text(encoding='utf-8'),
                 'Запрещено маскировать libxml2 CVE')


if __name__ == '__main__':
    try:
        assert_policy(Path(__file__).resolve().parents[2])
    except (AssertionError, OSError) as error:
        print(f'INFRA POLICY BLOCKED: {error}', file=sys.stderr)
        sys.exit(1)
    print('INFRA POLICY OK: Noble digest, образ для CI/release, Trivy и ручной допуск')
