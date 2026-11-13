# syntax=docker/dockerfile:1.7
# SafeAI-Desk/backend/Dockerfile

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

# Spring Boot собирает один исполняемый JAR. Исключаем архивы исходников и тестов.
# При отсутствии JAR или нескольких подходящих файлах сборка завершится ошибкой.
# find -exec не требует промежуточной shell-переменной.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -q -DskipTests clean package \
    && find target -maxdepth 1 -type f \
        -name 'safeai-backend-*.jar' \
        ! -name '*-sources.jar' \
        ! -name '*-javadoc.jar' \
        ! -name '*-tests.jar' \
        -exec sh -ec 'test "$#" -eq 1 && cp -- "$1" /app/app.jar' sh {} + \
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

# Обновляем системные пакеты и не скрываем ошибки менеджера пакетов.
RUN apt-get -o Acquire::Retries=0 update \
    && apt-get -o Acquire::Retries=0 upgrade -y \
    && apt-get -o Acquire::Retries=0 install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 safeai \
    && useradd --system --uid 10001 --gid safeai \
        --home-dir /app --shell /usr/sbin/nologin safeai

COPY --from=build \
    --chown=safeai:safeai \
    /app/app.jar \
    /app/app.jar

USER 10001:10001

EXPOSE 8080 9091

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:InitialRAMPercentage=25 -XX:+ExitOnOutOfMemoryError" \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8

STOPSIGNAL SIGTERM

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
