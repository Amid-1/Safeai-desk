# SafeAi-Desk/infra/nginx/Dockerfile
# syntax=docker/dockerfile:1.7

ARG NODE_IMAGE=node:24.19.0-alpine
ARG NGINX_IMAGE=nginxinc/nginx-unprivileged:stable-alpine

FROM ${NODE_IMAGE} AS build

WORKDIR /workspace/frontend

COPY frontend/package.json frontend/package-lock.json ./

RUN --mount=type=cache,target=/root/.npm \
    npm ci

COPY frontend/ ./

RUN npm run check


FROM ${NGINX_IMAGE} AS runtime

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

COPY --from=build \
    /workspace/frontend/dist/ \
    /usr/share/nginx/html/

USER 101

EXPOSE 8080

STOPSIGNAL SIGQUIT
