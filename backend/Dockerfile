FROM eclipse-temurin:21-jdk AS build

WORKDIR /app

COPY .mvn .mvn
COPY mvnw pom.xml ./

RUN chmod +x mvnw
RUN ./mvnw -q dependency:go-offline

COPY src src

RUN ./mvnw -q package


FROM eclipse-temurin:21-jre

WORKDIR /app

RUN groupadd --system safeai \
    && useradd --system --gid safeai --home-dir /app --shell /usr/sbin/nologin safeai

COPY --from=build --chown=safeai:safeai /app/target/*.jar app.jar

USER safeai

EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:InitialRAMPercentage=50 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]