pluginManagement {
    repositories {
        // 国内镜像（腾讯云）优先
        maven(url = "https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        maven(url = "https://mirrors.cloud.tencent.com/gradle/")
        // fallback：官方仓库（走代理兜底）
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 国内镜像（腾讯云）优先
        maven(url = "https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        maven(url = "https://jitpack.io")
        // Shizuku 官方 Maven
        maven(url = "https://dl.bintray.com/rikkaw/Shizuku/")
        // fallback：官方仓库（走代理兜底）
        google()
        mavenCentral()
    }
}

rootProject.name = "Genshin-SpikeGuard"
include(":app")
