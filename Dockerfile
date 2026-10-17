# ShopServer Docker 镜像
# 预编译方案：项目代码和序列化器在本地 installDist 生成
# Docker 只做 COPY + 运行，彻底规避远端构建环境序列化插件静默失效问题
#
# 本地预编译命令（开发者在自己机器上跑一次即可）：
#   ./gradlew clean installDist
#   # 然后把 build/install/ShopServer/ 复制到 prebuilt/ShopServer/
#
# 为什么不用远端构建？
#   SnapDeploy 的 AWS CodeBuild 跑 Gradle 时，kotlinx-serialization 编译器插件
#   会静默失效——BUILD SUCCESSFUL 但 jar 里没有 $$serializer 类，运行时炸掉。
#   本地编译则完全正常（已验证 82 个序列化器类全部生成）。

FROM openjdk:17-jre-slim
WORKDIR /app

# 拷贝预编译产物（本地 installDist 输出，已含所有依赖 jar + $$serializer）
COPY prebuilt/ShopServer /app

# 启动前诊断：如果预编译产物缺了序列化器，Docker 启动时直接报错
RUN jar tf /app/lib/ShopServer-0.1.0.jar | grep 'BaseResponse.*serializer' \
    && echo "✓ Prebuilt serializer verified OK" \
    || (echo "✗ FATAL: Prebuilt ShopServer-0.1.0.jar is missing $$serializer class! Rebuild locally and resubmit." && exit 1)

EXPOSE 8080

# 用 java -cp 通配符加载所有依赖 jar
ENTRYPOINT ["java", "-cp", "/app/lib/*", "org.lzy.shop.server.ApplicationKt"]
