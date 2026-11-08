# syntax=docker/dockerfile:1.7
# SafeAI-Desk/infra/postgres/Dockerfile
# Ubuntu Noble PostgreSQL 16. Restored-data approval is required before changing a live PostgreSQL volume.
ARG UBUNTU_IMAGE=ubuntu:24.04@sha256:fe81560990fae86dc4743202b2cb4d3df98d0bcf949173cecef15ce1127ef852
ARG GOSU_BUILDER_IMAGE=golang:1.27.3-trixie@sha256:c65ecfe613b539ac74c62a4c3b2c27e8f8a6076384c8f27e8a3c0f3be17cc385
ARG ENTRYPOINT_SOURCE_IMAGE=pgvector/pgvector:0.8.7-pg16-trixie@sha256:9cf8b511f0e9a2c209b61430fc139f1ab9c75edbb31fb1751db6617b246a897a

FROM ${GOSU_BUILDER_IMAGE} AS gosu-build
RUN set -eux; \
    git clone --depth 1 --branch 1.20 https://github.com/tianon/gosu.git /src/gosu; \
    cd /src/gosu; \
    test "$(git rev-parse HEAD)" = 29b939aa5bd4dc588af765b8248f6e39150d2565; \
    CGO_ENABLED=0 go build -trimpath -ldflags='-s -w' -o /out/gosu .; \
    /out/gosu --version; \
    go version -m /out/gosu

# Copy ONLY official entrypoint scripts, not Debian libraries or binaries.
FROM ${ENTRYPOINT_SOURCE_IMAGE} AS upstream-entrypoint

FROM ${UBUNTU_IMAGE} AS postgres-core
ARG POSTGRES_APT_VERSION=16.15-0ubuntu0.24.04.1
ARG SAFEAI_READER_GID=21001
ENV DEBIAN_FRONTEND=noninteractive \
    PG_MAJOR=16 \
    PGDATA=/var/lib/postgresql/data \
    LANG=en_US.utf8 \
    LC_ALL=en_US.utf8 \
    PATH="/usr/lib/postgresql/16/bin:${PATH}"
RUN set -eux; \
    if getent group 999 >/dev/null; then groupadd --system postgres; else groupadd --gid 999 postgres; fi; \
    useradd --uid 999 --gid postgres --home-dir /var/lib/postgresql --shell /bin/bash --system postgres; \
    install -d -o postgres -g postgres -m 1777 /var/lib/postgresql; \
    apt-get -o Acquire::Retries=3 update; \
    apt-get -o Acquire::Retries=3 upgrade -y; \
    apt-get -o Acquire::Retries=3 install -y --no-install-recommends \
      ca-certificates locales less libnss-wrapper xz-utils zstd postgresql-common; \
    sed -ri 's/^#?create_main_cluster[[:space:]]*=.*/create_main_cluster = false/' /etc/postgresql-common/createcluster.conf; \
    grep -Eq '^create_main_cluster[[:space:]]*=[[:space:]]*false' /etc/postgresql-common/createcluster.conf; \
    apt-get -o Acquire::Retries=3 install -y --no-install-recommends "postgresql-16=${POSTGRES_APT_VERSION}"; \
    test "$(dpkg-query -W -f='${Version}' postgresql-16)" = "${POSTGRES_APT_VERSION}"; \
    xml_ver="$(dpkg-query -W -f='${Version}' libxml2)"; \
    dpkg --compare-versions "$xml_ver" ge '2.9.14+dfsg-1.3ubuntu3.8'; \
    echo "libxml2 corrected: $xml_ver"; \
    echo 'en_US.UTF-8 UTF-8' >> /etc/locale.gen; \
    locale-gen; \
    mkdir -p /docker-entrypoint-initdb.d; \
    install -d -o postgres -g postgres -m 3777 /var/run/postgresql; \
    install -d -o postgres -g postgres -m 1777 /var/lib/postgresql/data; \
    sed -ri "s!^#?(listen_addresses)[[:space:]]*=[[:space:]]*[^[:space:]]+.*!\\1 = '*'!" /usr/share/postgresql/16/postgresql.conf.sample; \
    grep -F "listen_addresses = '*'" /usr/share/postgresql/16/postgresql.conf.sample; \
    case "$SAFEAI_READER_GID" in ''|*[!0-9]*) exit 1 ;; esac; \
    if getent group "$SAFEAI_READER_GID" >/dev/null; then \
      group="$(getent group "$SAFEAI_READER_GID" | cut -d: -f1)"; \
    else \
      group=safeai-secrets; groupadd -g "$SAFEAI_READER_GID" "$group"; \
    fi; \
    usermod -aG "$group" postgres; \
    rm -rf /var/lib/apt/lists/*; \
    postgres --version; \
    ldd /usr/lib/postgresql/16/bin/postgres | grep 'libxml2.so.2'; \
    ! ldd /usr/lib/postgresql/16/bin/postgres | grep -q 'not found'

# Build pgvector against exactly the same Ubuntu PostgreSQL ABI as the final runtime.
FROM postgres-core AS vector-build
ARG POSTGRES_APT_VERSION=16.15-0ubuntu0.24.04.1
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
      build-essential git ca-certificates "postgresql-server-dev-16=${POSTGRES_APT_VERSION}"; \
    git clone --depth 1 --branch v0.8.7 https://github.com/pgvector/pgvector.git /tmp/pgvector; \
    cd /tmp/pgvector; \
    test "$(git rev-parse HEAD)" = f37c13f68b57d2c3472b2214fbcff699d6d34876; \
    make OPTFLAGS=''; \
    make install; \
    install -d /vector-output/lib /vector-output/extension; \
    cp -a /usr/lib/postgresql/16/lib/vector.so /vector-output/lib/; \
    cp -a /usr/share/postgresql/16/extension/vector* /vector-output/extension/; \
    test -s /vector-output/extension/vector--0.8.7.sql; \
    test -s /vector-output/extension/vector.control

FROM postgres-core AS runtime
ARG APP_VERSION=dev
ARG VCS_REF=unknown
ARG BUILD_DATE=unknown
ARG SOURCE_URL=unknown
ARG SAFEAI_READER_GID=21001
LABEL org.opencontainers.image.title="SafeAI PostgreSQL Noble" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.source="${SOURCE_URL}" \
      io.safeai.secret-gid="${SAFEAI_READER_GID}" \
      io.safeai.release-status="release-controlled"
COPY --from=vector-build /vector-output/lib/vector.so /usr/lib/postgresql/16/lib/vector.so
COPY --from=vector-build /vector-output/extension/ /usr/share/postgresql/16/extension/
COPY --from=gosu-build --chmod=0755 /out/gosu /usr/local/bin/gosu
COPY --from=upstream-entrypoint --chmod=0755 /usr/local/bin/docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
COPY --from=upstream-entrypoint --chmod=0755 /usr/local/bin/docker-ensure-initdb.sh /usr/local/bin/docker-ensure-initdb.sh
COPY --chmod=0755 infra/postgres/init/10-create-app-roles.sh /opt/safeai/10-create-app-roles.sh
RUN set -eux; \
    ln -s docker-ensure-initdb.sh /usr/local/bin/docker-enforce-initdb.sh; \
    gosu --version; gosu nobody true; \
    test "$(stat -c %u /var/lib/postgresql)" = '999'; \
    test -f /usr/share/postgresql/16/extension/vector.control; \
    test -f /usr/lib/postgresql/16/lib/vector.so; \
    postgres --version; \
    ! ldd /usr/lib/postgresql/16/bin/postgres | grep -q 'not found'
VOLUME /var/lib/postgresql/data
ENTRYPOINT ["docker-entrypoint.sh"]
STOPSIGNAL SIGINT
EXPOSE 5432
CMD ["postgres"]
