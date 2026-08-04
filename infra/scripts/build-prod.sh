#!/usr/bin/env bash
set -Eeuo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
infra_dir="$(cd -- "$script_dir/.." && pwd)"
repo_dir="$(cd -- "$infra_dir/.." && pwd)"
env_file="${1:-$repo_dir/backend/.env.prod}"
[[ -f "$env_file" ]] || { echo "Production env file does not exist: $env_file" >&2; exit 1; }

set -a
# shellcheck disable=SC1090
source "$env_file"
set +a

for name in APP_VERSION SOURCE_URL JAVA_BUILD_IMAGE JAVA_RUNTIME_IMAGE NGINX_BASE_IMAGE POSTGRES_BASE_IMAGE SAFEAI_BACKEND_BUILD_TAG SAFEAI_NGINX_BUILD_TAG SAFEAI_POSTGRES_BUILD_TAG SAFEAI_SECRET_GID; do
    [[ -n "${!name:-}" && "${!name}" != *REPLACE_WITH* ]] || { echo "Set a real value for $name" >&2; exit 1; }
done

if [[ "${SAFEAI_REQUIRE_IMMUTABLE_IMAGES:-true}" == "true" ]]; then
    for name in JAVA_BUILD_IMAGE JAVA_RUNTIME_IMAGE NGINX_BASE_IMAGE POSTGRES_BASE_IMAGE; do
        [[ "${!name}" == *@sha256:* ]] || {
            echo "$name must contain @sha256 for a production build" >&2
            exit 1
        }
    done
fi

vcs_ref="$(git -C "$repo_dir" rev-parse HEAD)"
build_date="$(date -u +'%Y-%m-%dT%H:%M:%SZ')"

(
    cd "$repo_dir/backend"
    project_version="$(./mvnw -q -DforceStdout help:evaluate -Dexpression=project.version)"
    [[ "$project_version" == "$APP_VERSION" ]] || {
        echo "POM version $project_version does not match APP_VERSION $APP_VERSION" >&2
        exit 1
    }
    ./mvnw clean verify
)

if [[ -f "$repo_dir/frontend/package-lock.json" ]]; then
    (
        cd "$repo_dir/frontend"
        npm ci
        npm run build
    )
fi
[[ -f "$repo_dir/frontend/dist/index.html" ]] || { echo "frontend/dist/index.html is missing" >&2; exit 1; }


docker build \
    --file "$repo_dir/infra/postgres/Dockerfile" \
    --build-arg "POSTGRES_BASE_IMAGE=$POSTGRES_BASE_IMAGE" \
    --build-arg "SAFEAI_SECRET_GID=$SAFEAI_SECRET_GID" \
    --build-arg "APP_VERSION=$APP_VERSION" \
    --build-arg "VCS_REF=$vcs_ref" \
    --build-arg "BUILD_DATE=$build_date" \
    --build-arg "SOURCE_URL=$SOURCE_URL" \
    --tag "$SAFEAI_POSTGRES_BUILD_TAG" \
    "$repo_dir"

docker build \
    --file "$repo_dir/backend/Dockerfile" \
    --build-arg "BUILD_IMAGE=$JAVA_BUILD_IMAGE" \
    --build-arg "RUNTIME_IMAGE=$JAVA_RUNTIME_IMAGE" \
    --build-arg "APP_VERSION=$APP_VERSION" \
    --build-arg "VCS_REF=$vcs_ref" \
    --build-arg "BUILD_DATE=$build_date" \
    --build-arg "SOURCE_URL=$SOURCE_URL" \
    --tag "$SAFEAI_BACKEND_BUILD_TAG" \
    "$repo_dir/backend"

docker build \
    --file "$repo_dir/infra/nginx/Dockerfile" \
    --build-arg "NGINX_BASE_IMAGE=$NGINX_BASE_IMAGE" \
    --build-arg "APP_VERSION=$APP_VERSION" \
    --build-arg "VCS_REF=$vcs_ref" \
    --build-arg "BUILD_DATE=$build_date" \
    --build-arg "SOURCE_URL=$SOURCE_URL" \
    --tag "$SAFEAI_NGINX_BUILD_TAG" \
    "$repo_dir"

echo "Images built: backend, frontend/Nginx, and hardened PostgreSQL."
echo "Push, scan, sign, and set digest refs in backend/.env.prod."
