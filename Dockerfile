# Expects the jar built by `./mvnw verify` (CI builds it first).
FROM eclipse-temurin:21-jre

RUN groupadd --system --gid 10001 nms \
    && useradd --system --uid 10001 --gid nms nms \
    && mkdir -p /app/data \
    && chown nms:nms /app/data
WORKDIR /app
COPY target/nms-*.jar app.jar
USER 10001:10001

ENV NMS_DATA_DIR=/app/data
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
