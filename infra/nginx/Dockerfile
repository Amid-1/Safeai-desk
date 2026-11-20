# SafeAi-Desk/infra/nginx/Dockerfile
# syntax=docker/dockerfile:1.7

ARG NODE_IMAGE=node:24.19.0-alpine
# Official unprivileged NGINX, pinned to the patched multi-platform release.
ARG NGINX_IMAGE=ghcr.io/nginx/nginx-unprivileged:1.30.7-alpine@sha256:0c53a1f2890c3526bfd201440f0fd3970e5b78f80630f58284aad0d34b5ec7bd

FROM ${NODE_IMAGE} AS build

WORKDIR /workspace/frontend

COPY frontend/package.json frontend/package-lock.json ./

RUN --mount=type=cache,target=/root/.npm \
    npm ci

# frontend/package.json runs ../scripts/verify-build.mjs from `npm run build`.
# Keep the verifier inside the Docker build context instead of silently
# skipping the production bundle check.
COPY scripts/verify-build.mjs /workspace/scripts/verify-build.mjs

COPY .github/workflows/browser-ci.yml /workspace/.github/workflows/browser-ci.yml

COPY frontend/ ./

RUN npm run check


FROM ${NGINX_IMAGE} AS runtime

# nginx-unprivileged по умолчанию работает от UID 101. Для обновления
# пакетов Alpine нужны права root ТОЛЬКО на этапе сборки.
USER root
# Не подавляем Trivy: реально обновляем уязвимые пакеты и
# требуем исправленный Brotli (CVE-2026-111497).
RUN set -eux; \
    apk --no-cache upgrade; \
    apk --no-cache add 'brotli-libs>=1.2.1-r0'; \
    nginx_pkg="$(apk info -v nginx)"; \
    case "$nginx_pkg" in nginx-*) ;; *) exit 1 ;; esac; \
    case "$(apk version -t "${nginx_pkg#nginx-}" 1.30.7-r0)" in \
      '='|'>') ;; *) echo "BLOCKED: vulnerable NGINX package: $nginx_pkg" >&2; exit 1 ;; \
    esac; \
    nginx -v

ARG APP_VERSION=dev
ARG VCS_REF=unknown
ARG BUILD_DATE=unknown
ARG SOURCE_URL=unknown

LABEL org.opencontainers.image.title="SafeAI Frontend" \
      org.opencontainers.image.description="SafeAI Desk React frontend and edge proxy" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.source="${SOURCE_URL}"

COPY infra/nginx/nginx.conf \
    /etc/nginx/conf.d/default.conf

COPY infra/nginx/security-headers.conf \
    /etc/nginx/snippets/safeai-security-headers.conf

COPY --from=build \
    /workspace/frontend/dist/ \
    /usr/share/nginx/html/

USER 101

EXPOSE 8080

STOPSIGNAL SIGQUIT
