# Expects the jar built by `./mvnw verify` (CI builds it first).
FROM eclipse-temurin:21-jre

RUN groupadd --system nms && useradd --system --gid nms nms
WORKDIR /app
COPY target/nms-*.jar app.jar
USER nms

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
