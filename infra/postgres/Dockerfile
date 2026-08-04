# syntax=docker/dockerfile:1.7

ARG POSTGRES_BASE_IMAGE=postgres:16

FROM ${POSTGRES_BASE_IMAGE}

ARG APP_VERSION=dev
ARG VCS_REF=unknown
ARG BUILD_DATE=unknown
ARG SOURCE_URL=unknown
ARG SAFEAI_SECRET_GID=21001

LABEL org.opencontainers.image.title="SafeAI PostgreSQL" \
      org.opencontainers.image.description="PostgreSQL with SafeAI role bootstrap and secret-reader group" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.source="${SOURCE_URL}" \
      io.safeai.secret-gid="${SAFEAI_SECRET_GID}"

RUN set -eux; \
    case "${SAFEAI_SECRET_GID}" in \
        ''|*[!0-9]*) \
            echo "SAFEAI_SECRET_GID must be numeric" >&2; \
            exit 1; \
            ;; \
    esac; \
    if getent group "${SAFEAI_SECRET_GID}" >/dev/null; then \
        usermod \
            --append \
            --groups "$(getent group "${SAFEAI_SECRET_GID}" | cut -d: -f1)" \
            postgres; \
    else \
        groupadd \
            --gid "${SAFEAI_SECRET_GID}" \
            safeai-secrets; \
        usermod \
            --append \
            --groups safeai-secrets \
            postgres; \
    fi

COPY --chmod=0755 \
    infra/postgres/init/10-create-app-roles.sh \
    /docker-entrypoint-initdb.d/10-create-app-roles.sh