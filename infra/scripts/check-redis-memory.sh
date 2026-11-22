#!/usr/bin/env bash
# SafeAI-Desk/infra/scripts/check-redis-memory.sh
# Read-only probe. All arguments are docker compose options (before exec).
set -euo pipefail

docker compose "$@" exec -T redis sh -eu -c '
    if [ -r /run/secrets/redis_password ]; then
        REDISCLI_AUTH=$(tr -d "\r\n" < /run/secrets/redis_password)
    else
        REDISCLI_AUTH=${REDIS_PASSWORD:?Redis credentials unavailable}
    fi
    test -n "$REDISCLI_AUTH"
    export REDISCLI_AUTH
    policy=$(redis-cli --raw CONFIG GET maxmemory-policy)
    test "$(printf "%s\n" "$policy" | tail -n 1)" = noeviction
    memory=$(redis-cli --raw INFO memory)
    stats=$(redis-cli --raw INFO stats)
    printf "%s\n%s\n" "$memory" "$stats" | tr -d "\r" | awk -F: '\''
        $1 ~ /^(used_memory|used_memory_rss|maxmemory|mem_not_counted_for_evict|evicted_keys)$/ {
            if ($2 !~ /^[0-9]+$/ || seen[$1]++) { invalid = 1; exit 2 }
            count++
            v[$1] = $2 + 0
        }
        END {
            if (invalid || count != 5 || v["maxmemory"] <= 0) exit 2
            used = v["used_memory"] - v["mem_not_counted_for_evict"]
            if (used < 0) exit 2
            printf "Redis: accounted_bytes=%.0f maxmemory_bytes=%.0f rss_bytes=%.0f evicted_keys=%.0f\n",
                used, v["maxmemory"], v["used_memory_rss"], v["evicted_keys"]
            if (used >= 0.8 * v["maxmemory"] || v["evicted_keys"] != 0) exit 1
        }
    '\''
    if [ -r /sys/fs/cgroup/memory.current ] && [ -r /sys/fs/cgroup/memory.max ]; then
        current=$(cat /sys/fs/cgroup/memory.current)
        limit=$(cat /sys/fs/cgroup/memory.max)
    elif [ -r /sys/fs/cgroup/memory/memory.usage_in_bytes ] &&
         [ -r /sys/fs/cgroup/memory/memory.limit_in_bytes ]; then
        current=$(cat /sys/fs/cgroup/memory/memory.usage_in_bytes)
        limit=$(cat /sys/fs/cgroup/memory/memory.limit_in_bytes)
    else
        echo "Redis cgroup memory unavailable" >&2
        exit 2
    fi
    printf "Redis cgroup: current_bytes=%s limit_bytes=%s\n" "$current" "$limit"
    awk -v current="$current" -v limit="$limit" '\''
        BEGIN {
            if (current !~ /^[0-9]+$/ || limit !~ /^[0-9]+$/ || limit <= 0 || limit >= 2^60) exit 2
            if (current >= 0.8 * limit) exit 1
        }
    '\''
'
