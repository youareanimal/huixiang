// 网关是独立构建：它只要 tsproto 的源码，不碰 Android 那一套。
// 这样在服务器上部署不需要 Android SDK，Docker 镜像也不用复制整个 app/ 目录。
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    // 版本继续跟主项目共用一个目录，避免两边 Kotlin 版本漂移
    versionCatalogs {
        create("libs") { from(files("../../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "webgateway"
