FROM eclipse-temurin:25.0.4_7-jdk-jammy AS build
WORKDIR /workspace
COPY backend/ .
RUN --mount=type=cache,target=/root/.gradle chmod +x gradlew && ./gradlew bootJar --no-daemon
COPY infra/docker/Healthcheck.java /tmp/Healthcheck.java
RUN javac -d /health /tmp/Healthcheck.java

FROM eclipse-temurin:25.0.4_7-jre-jammy AS runtime
RUN groupadd --gid 10001 ledgerflow && useradd --uid 10001 --gid ledgerflow --no-create-home ledgerflow
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/build/libs/ledgerflow-0.1.0-SNAPSHOT.jar /app/app.jar
COPY --from=build --chown=10001:10001 /health /app/health
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -Duser.timezone=UTC --enable-native-access=ALL-UNNAMED"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM runtime AS migration
ENTRYPOINT ["java", "-Dloader.main=com.ledgerflow.MigrationApplication", "-cp", "/app/app.jar", "org.springframework.boot.loader.launch.PropertiesLauncher"]

FROM runtime AS application
