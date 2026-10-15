pluginManagement {
    repositories {
        // 阿里云镜像优先（国内 Sealos 构建直接命中，避免官方源超时）
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        // 官方源兜底
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 阿里云镜像优先（国内加速）
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        // 官方源兜底
        mavenCentral()
    }
}

rootProject.name = "ShopServer"
