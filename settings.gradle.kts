pluginManagement {
    repositories {
        // 官方源优先（海外云平台构建直接命中，国内 Sealos 等国内源兜底）
        gradlePluginPortal()
        mavenCentral()
        // 阿里云镜像兜底（国内构建加速，海外构建时作为备份）
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 官方源优先（海外云平台构建直接命中）
        mavenCentral()
        // 阿里云镜像兜底（国内构建加速）
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}

rootProject.name = "ShopServer"
