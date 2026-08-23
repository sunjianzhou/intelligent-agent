# 两阶段构建，先 Maven 编译，再用 JRE 运行，镜像体积更小
# ── 阶段一：编译 ──────────────────────────────────────────
FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /app
COPY pom.xml .
# 复制自定义 Maven 配置（阿里云镜像）
COPY settings.xml /root/.m2/settings.xml
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests -B

# ── 阶段二：运行 ──────────────────────────────────────────
FROM eclipse-temurin:21-jre

WORKDIR /app
# 健康检查依赖 curl（eclipse-temurin 基础镜像默认不带）
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

# -Xms64m        初始堆小，冷启动 RSS 低
# -Xmx400m       上限 400 MB（足够 Spring Boot，下同 mem_limit 留出 JVM 非堆余量）
# -XX:+UseG1GC   空闲时将堆内存还给 OS
ENTRYPOINT ["java", \
  "-Xms64m", "-Xmx400m", \
  "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=200", \
  "-XX:+UseStringDeduplication", \
  "-jar", "app.jar"]
