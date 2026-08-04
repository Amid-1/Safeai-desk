# syntax=docker/dockerfile:1.7
# syntax=docker/dockerfile:1.7

ARG BUILD_IMAGE=eclipse-temurin:21-jdk
ARG RUNTIME_IMAGE=eclipse-temurin:21-jre


FROM ${BUILD_IMAGE} AS build

WORKDIR /app

COPY .mvn .mvn
COPY mvnw pom.xml ./

RUN --mount=type=cache,target=/root/.m2 \
    chmod +x mvnw \
    && ./mvnw -q -DskipTests dependency:go-offline

COPY src src

RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -q -DskipTests clean package \
    && find target \
        -maxdepth 1 \
        -type f \
        -name '*.jar' \
        ! -name '*-sources.jar' \
        ! -name '*-javadoc.jar' \
        -exec cp '{}' /app/app.jar ';' \
        -quit \
    && test -s /app/app.jar


FROM ${RUNTIME_IMAGE} AS runtime

ARG APP_VERSION=dev
ARG VCS_REF=unknown
ARG BUILD_DATE=unknown
ARG SOURCE_URL=unknown

LABEL org.opencontainers.image.title="SafeAI Backend" \
      org.opencontainers.image.description="SafeAI Desk backend API" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.source="${SOURCE_URL}"

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd \
        --system \
        --gid 10001 \
        safeai \
    && useradd \
        --system \
        --uid 10001 \
        --gid safeai \
        --home-dir /app \
        --shell /usr/sbin/nologin \
        safeai

COPY --from=build \
    --chown=safeai:safeai \
    /app/app.jar \
    /app/app.jar

USER safeai

EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:InitialRAMPercentage=25 -XX:+ExitOnOutOfMemoryError"

STOPSIGNAL SIGTERM

ENTRYPOINT ["java", "-jar", "/app/app.jar"]