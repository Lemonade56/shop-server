# ShopServer Docker 镜像
# 使用 Ktor 官方 fat jar 构建：所有依赖（含编译器生成的 serializer 类）打包进一个 jar
# 彻底消除 installDist 方式可能存在的 classpath 分离导致 serializer 找不到的问题

# ---- 阶段 1：构建 ----
FROM openjdk:17-jdk-slim AS builder
WORKDIR /app

# 先拷贝 gradle wrapper 和依赖声明，利用 Docker 缓存层
COPY gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.jar
COPY gradle/wrapper/gradle-wrapper.properties gradle/wrapper/gradle-wrapper.properties
COPY gradlew .
COPY build.gradle.kts settings.gradle.kts gradle.properties ./

RUN chmod +x gradlew

# 再拷贝源码，这样代码变动不会导致依赖缓存失效
COPY src src

# clean 消除任何旧缓存；buildFatJar 由 ktor plugin 提供
# 产物：build/libs/ShopServer-all.jar（一个 jar 包含所有依赖 + 生成的 serializer 类）
RUN ./gradlew clean buildFatJar --no-daemon --stacktrace

# ---- 阶段 2：运行 ----
FROM openjdk:17-jre-slim
WORKDIR /app

# 只拷贝一个 fat jar（ktor plugin 生成）
COPY --from=builder /app/build/libs/ShopServer-all.jar /app/

EXPOSE 8080

# 直接 java -jar 运行
ENTRYPOINT ["java", "-jar", "/app/ShopServer-all.jar"]
