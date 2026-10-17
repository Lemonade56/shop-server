# ShopServer Docker 镜像
# 多阶段构建：第一阶段用 JDK 编译，第二阶段只用 JRE 运行
# 使用 Gradle 官方 installDist，每个依赖独立 jar，避免 fat jar 打包丢失 SPI 元数据

# ---- 阶段 1：构建 ----
FROM openjdk:17-jdk-slim AS builder
WORKDIR /app

# 先拷贝 gradle wrapper 和依赖声明，利用 Docker 缓存层
COPY gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.jar
COPY gradle/wrapper/gradle-wrapper.properties gradle/wrapper/gradle-wrapper.properties
COPY gradlew .
COPY build.gradle.kts settings.gradle.kts gradle.properties ./

# 给 gradlew 加执行权限，先跑一次依赖下载
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon --stacktrace

# 再拷贝源码，这样代码变动不会导致依赖缓存失效
COPY src src

# 生成标准分发包（installDist → build/install/ShopServer/）
RUN ./gradlew installDist --no-daemon --stacktrace

# 诊断：验证序列化编译器插件是否生效 —— 如果 $$serializer 类缺失则立即失败
RUN jar tf build/install/ShopServer/lib/ShopServer-0.1.0.jar | grep 'BaseResponse.*serializer' \
    && echo "✓ Serializer classes generated OK" \
    || (echo "✗ SERIALIZER MISSING — kotlinx-serialization plugin did not run!" && exit 1)

# ---- 阶段 2：运行 ----
FROM openjdk:17-jre-slim
WORKDIR /app

# 拷贝 installDist 的产物（每个依赖 jar 独立，无 SPI 丢失风险）
COPY --from=builder /app/build/install/ShopServer /app

# 启动脚本加执行权限
RUN chmod +x /app/bin/ShopServer

EXPOSE 8080

# 用 Gradle 生成的标准启动脚本
ENTRYPOINT ["/app/bin/ShopServer"]
