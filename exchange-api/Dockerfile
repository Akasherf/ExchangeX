# Build from repository root: docker build -f exchange-api/Dockerfile .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml pom.xml
COPY matching-engine/pom.xml matching-engine/pom.xml
COPY exchange-api/pom.xml exchange-api/pom.xml
RUN mvn -B -ntp -DskipTests dependency:go-offline
COPY matching-engine/src matching-engine/src
COPY exchange-api/src exchange-api/src
RUN mvn -B -ntp -DskipTests clean package

FROM eclipse-temurin:21-jre-jammy AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates \
  && rm -rf /var/lib/apt/lists/* \
  && groupadd --system exchangex && useradd --system --gid exchangex --home-dir /app exchangex
WORKDIR /app
COPY --from=build --chown=exchangex:exchangex /src/exchange-api/target/exchange-api-0.1.0-SNAPSHOT.jar /app/exchange-api.jar
USER exchangex
EXPOSE 8085 9090
ENV SPRING_PROFILES_ACTIVE=docker
ENTRYPOINT ["java", "-jar", "/app/exchange-api.jar"]
