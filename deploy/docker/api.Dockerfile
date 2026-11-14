FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache tzdata
ENV TZ=Asia/Shanghai
ENV AGENTUM_TIMEZONE=Asia/Shanghai
WORKDIR /app
COPY apps/api/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
