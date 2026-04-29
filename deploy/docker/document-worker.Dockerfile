FROM gradle:8.10.2-jdk21-alpine AS builder
WORKDIR /app
COPY . .
RUN gradle :workers:document-worker:bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/workers/document-worker/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
