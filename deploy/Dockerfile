# 多阶段构建：前端(Vue) + 后端(Spring Boot) 打进一个 jar；运行时按 activity.role 扮演 console 或 decision。
# 构建：docker build -f deploy/Dockerfile -t drools-activity:latest .
# （-Pfrontend 让 frontend-maven-plugin 在构建容器内下载 node 并 build，产物拷进 static/ui）

# ---- 构建阶段 ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# 先拷 pom + 前端 manifest，利用层缓存（依赖不变则不重装）
COPY pom.xml ./
COPY frontend/package.json frontend/package-lock.json ./frontend/
COPY .mvn/ .mvn/
COPY mvnw ./
# 拷源码
COPY src ./src
COPY frontend ./frontend
# 打包（含前端）；跳测试（CI 另跑）。frontend-maven-plugin 会在容器内装 node。
RUN ./mvnw -q -Pfrontend -Dmaven.test.skip=true clean package

# ---- 运行阶段 ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/*.jar app.jar
EXPOSE 8080
# 容量护栏：Metaspace 上界配合 KieBase 缓存足迹预算（见 docs/50-P0-5-memory-capacity-model.md）
ENV JAVA_OPTS="-XX:MaxMetaspaceSize=256m -XX:+UseG1GC"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
