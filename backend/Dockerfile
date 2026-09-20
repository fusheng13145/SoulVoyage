# 多阶段：构建层出 fat jar，运行层只留 JRE（约 260MB）
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /m
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /m/target/soulvoyage-backend-*.jar app.jar
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --retries=5 \
  CMD curl -fsS http://127.0.0.1:8080/actuator/health | grep -q UP || exit 1
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
