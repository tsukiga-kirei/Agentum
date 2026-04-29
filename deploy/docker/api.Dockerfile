FROM gradle:8.10.2-jdk21-alpine AS builder
WORKDIR /app
COPY . .
RUN gradle :apps:api:bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/apps/api/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
