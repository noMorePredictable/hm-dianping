FROM eclipse-temurin:17-jre

WORKDIR /app

# CI 先执行 mvn package，再把唯一的业务 jar 放进精简 JRE 镜像。
COPY target/hm-dianping-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8081

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
