#!/usr/bin/env python3
"""Синтаксис встроенного SSH-скрипта и критичные запреты Production deploy."""
from __future__ import annotations

import argparse
from pathlib import Path
import subprocess
import sys


WORKFLOW = Path('.github/workflows/production-deploy.yml')
STEP = '      - name: Публикация и запуск production deploy'
BLOCK = '        run: |'
REMOTE_OPEN = 'cat > "$RUNNER_TEMP/safeai-deploy-remote.sh" <<\'REMOTE\''


def require(value: bool, message: str) -> None:
    if not value:
        raise ValueError(message)


def get_shell(text: str) -> tuple[str, str]:
    """Достаёт последний блок run и скрипт, передаваемый по SSH."""
    require(text.count(STEP) == 1, 'Не найдена ровно одна deploy-ступень')
    section = text.split(STEP, 1)[1]
    require(section.count(BLOCK) == 1, 'Нет единственного deploy run block')
    rest = section.split(BLOCK, 1)[1]
    lines = rest.splitlines()
    shell_lines: list[str] = []
    for line in lines[1:]:
        if line and not line.startswith('          '):
            break
        shell_lines.append(line[10:] if line else '')
    shell = '\n'.join(shell_lines) + '\n'
    require(REMOTE_OPEN in shell, 'Не найден защищённый heredoc удалённого скрипта')
    remote_lines = shell.split(REMOTE_OPEN, 1)[1].splitlines()
    require(remote_lines[0] == '', 'Неверная структура remote heredoc')
    require(remote_lines.count('REMOTE') == 1, 'Ожидается один закрывающий REMOTE')
    remote = '\n'.join(remote_lines[1:remote_lines.index('REMOTE')]) + '\n'
    return shell, remote


def validate(text: str) -> None:
    shell, remote = get_shell(text)
    for number, line in enumerate(shell.splitlines(), 1):
        if line.rstrip().endswith('\\'):
            following = shell.splitlines()[number:]
            require(bool(following) and bool(following[0].strip()),
                    f'Пустая строка после Bash continuation, line {number}')
    for name, source in [('runner', shell), ('remote', remote)]:
        result = subprocess.run(['bash', '-n'], input=source, text=True,
                                capture_output=True, check=False)
        require(result.returncode == 0, f'{name}: Bash syntax: {result.stderr.strip()}')
    require('printf -v remote_command "$remote_fmt" \\' in shell,
            'Отсутствует безопасная сборка SSH-команды')
    require('ssh "${ssh_opts[@]}" "$target" "$remote_command"' in shell,
            'Отсутствует SSH запуск собранной команды')
    require('[[ "$current_pg_image" == "$SAFEAI_POSTGRES_IMAGE" ]]' in remote,
            'Снята проверка текущего PostgreSQL image')
    require('docker volume inspect safeai_postgres_data' in remote,
            'Снята блокировка неизвестного PGDATA volume')
    require('git checkout --detach "$SAFEAI_RELEASE_SHA"' in remote,
            'Отсутствует фиксация SHA релиза')
    require('python3 infra/scripts/update-prod-images.py' in remote,
            'Отсутствует проверяемое обновление immutable images')
    require('bash infra/scripts/deploy-prod.sh backend/.env.prod' in remote,
            'Отсутствует штатный защищённый deploy script')
    require('docker compose down -v' not in shell and 'docker volume rm' not in shell,
            'Запрещены разрушительные Docker-volume команды')


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--workflow', type=Path, default=WORKFLOW)
    args = parser.parse_args()
    try:
        validate(args.workflow.read_text(encoding='utf-8'))
    except (OSError, ValueError) as error:
        print(f'PRODUCTION DEPLOY SYNTAX BLOCKED: {error}', file=sys.stderr)
        return 1
    print('PRODUCTION DEPLOY SYNTAX OK: runner, remote, immutable PGDATA guards')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
