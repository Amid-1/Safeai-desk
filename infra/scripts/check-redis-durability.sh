#!/usr/bin/env bash
# Read-only; pass Docker Compose options exactly as for check-redis-memory.sh.
set -euo pipefail
docker compose "$@" exec -T redis sh -eu -c '
    if [ -r /run/secrets/redis_password ]; then
        REDISCLI_AUTH=$(tr -d "\r\n" < /run/secrets/redis_password)
    else
        REDISCLI_AUTH=${REDIS_PASSWORD:?Redis credentials unavailable}
    fi
    test -n "$REDISCLI_AUTH"
    export REDISCLI_AUTH
    check_config() {
        actual=$(redis-cli -e --raw CONFIG GET "$1")
        if [ "$actual" != "$(printf "%s\n%s" "$1" "$2")" ]; then
            echo "Redis durability setting rejected: $1" >&2
            exit 1
        fi
    }
    check_config appendonly yes
    check_config appendfsync always
    check_config no-appendfsync-on-rewrite no
    check_config aof-load-truncated no
    persistence=$(redis-cli -e --raw INFO persistence)
    printf "%s\n" "$persistence" | tr -d "\r" | awk -F: '\''
        $1 ~ /^(aof_enabled|aof_last_write_status|loading)$/ { if (seen[$1]++) bad=1; v[$1]=$2 }
        END { if (bad || v["aof_enabled"]!="1" || v["aof_last_write_status"]!="ok" || v["loading"]!="0") exit 1 }
    '\''
    echo "PASS: Redis AOF enabled, fsync always, writes healthy, truncated AOF rejected"
'
