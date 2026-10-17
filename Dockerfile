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

# 诊断：确认序列化器类在多阶段拷贝后仍然存在
RUN jar tf /app/lib/ShopServer-0.1.0.jar | grep 'BaseResponse.*serializer' \
    && echo "✓ stage2 serializer OK" \
    || (echo "✗ stage2 serializer MISSING after COPY!" && exit 1)

# 检查 lib 目录实际有多少 jar（排查空拷贝）
RUN ls /app/lib/*.jar | wc -l | xargs -I{} echo "✓ lib jar count: {}"

EXPOSE 8080

# 绕过 Gradle 生成的 bin/ShopServer 脚本，直接用 java -cp
# installDist 脚本在某些环境下 classpath 装配有问题，导致运行时找不到 $$serializer
ENTRYPOINT ["java", "-cp", "/app/lib/*", "org.lzy.shop.server.ApplicationKt"]
