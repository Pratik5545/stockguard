# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
COPY src/main/ src/main/
RUN --mount=type=cache,target=/root/.m2 ./mvnw --batch-mode --no-transfer-progress --update-snapshots -Dmaven.test.skip=true package

FROM eclipse-temurin:25-jre-noble
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 stockguard \
    && useradd --uid 10001 --gid stockguard --no-create-home stockguard
WORKDIR /app
COPY --from=build --chown=stockguard:stockguard /workspace/target/stockguard-*.jar app.jar
USER stockguard
EXPOSE 8080
ENTRYPOINT ["java", "-Duser.timezone=UTC", "-jar", "app.jar"]
