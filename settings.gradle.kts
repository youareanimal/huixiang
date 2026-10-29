pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ShengQiaoTS"
include(":tsproto", ":app")

// 网页版的网关（web/gateway）是独立构建，不在这个工程里：
// 它只要 tsproto 的源码，不需要 Android SDK，服务器上反而更好部署。
// 构建它：cd web/gateway && ../../gradlew installDist
