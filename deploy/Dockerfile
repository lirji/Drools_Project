# M2.2 多模块物理拆分：一个 build 阶段构建整个 reactor，运行阶段按 MODULE 选装某个 app 的可执行 jar。
# compose 用同一 Dockerfile 以不同 --build-arg MODULE 产出 console / decision 两个镜像（build 阶段共享层缓存）。
#   docker build -f deploy/Dockerfile --build-arg MODULE=activity-console -t drools-platform/activity-console:latest ..
#   docker build -f deploy/Dockerfile --build-arg MODULE=activity-decision -t drools-platform/activity-decision:latest ..

# ---- 构建阶段（构建全部 4 模块；-Pfrontend 在容器内下载 node 并 build 前端进 console 的 static/ui）----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN ./mvnw -q -Pfrontend -Dmaven.test.skip=true clean package

# ---- 运行阶段（按 MODULE 选 jar）----
# ⚠ 必须是 JDK 不是 JRE：Step 24 PMML 走 trusty(efesto)，其 `KieMemoryCompiler.compile` 硬编码
#   CompilerType.NATIVE（JDK 的 ToolProvider.getSystemJavaCompiler），JRE 里没有 javac → PmmlService
#   构造时编译模型抛 KieMemoryCompilerException 启动即崩（drools-ecj 在 classpath 也没用，该路径不 fallback ECJ）。
FROM eclipse-temurin:21-jdk
WORKDIR /app
# curl 供 compose healthcheck 用（console 健康 = schema 已建好，decision 才 depends_on service_healthy）
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
# activity-console(含前端/Step1-24/写面，~104MB) | activity-decision(只读决策，~67MB，甩掉 kie-ci/dmn)
ARG MODULE=activity-console
COPY --from=build /src/${MODULE}/target/${MODULE}-*.jar app.jar
EXPOSE 8080
# 容量护栏：Metaspace 上界配合 KieBase 缓存足迹预算（见 docs/50-P0-5-memory-capacity-model.md）
ENV JAVA_OPTS="-XX:MaxMetaspaceSize=256m -XX:+UseG1GC"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
