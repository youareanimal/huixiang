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

// 网页版（网关 + 前端）已拆分到独立仓库 huixiang-web（https://github.com/MaXin-noob/huixiang-web），不再随本工程构建。
