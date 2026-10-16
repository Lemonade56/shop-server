# ShopServer Docker 镜像
# 多阶段构建：第一阶段用 JDK 编译，第二阶段只用 JRE 运行，镜像体积更小
# 基础镜像使用 Docker Hub 官方 openjdk，所有云平台都能拉取

# ---- 阶段 1：构建 ----
FROM openjdk:17-jdk-slim AS builder
WORKDIR /app

# 先拷贝 gradle wrapper 和依赖声明，利用 Docker 缓存层
COPY gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.jar
COPY gradle/wrapper/gradle-wrapper.properties gradle/wrapper/gradle-wrapper.properties
COPY gradlew .
COPY build.gradle.kts settings.gradle.kts gradle.properties local.properties ./

# 给 gradlew 加执行权限，先跑一次依赖下载（settings.gradle.kts 已配阿里云镜像，国内很快）
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon --stacktrace

# 再拷贝源码，这样代码变动不会导致依赖缓存失效
COPY src src

# 打 fat jar（shadowJar 正确处理 kotlinx-serialization 的 SPI 元数据）
RUN ./gradlew shadowJar --no-daemon --stacktrace

# ---- 阶段 2：运行 ----
FROM openjdk:17-jre-slim
WORKDIR /app

# 只拷贝构建产物，JRE 镜像比 JDK 小很多
COPY --from=builder /app/build/libs/ShopServer.jar app.jar

EXPOSE 8080

# 容器启动时运行
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
