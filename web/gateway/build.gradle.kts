import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 网页版的服务器端：用 tsproto 说 TeamSpeak 协议，再给浏览器一条 WebSocket。
// 纯 JVM，没有 Android 依赖——协议代码和 App 里是同一份，直接引源码进来，不需要 Android SDK。
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    sourceSets {
        main {
            // 和主构建里 :tsproto 模块的源码是同一批文件
            kotlin.srcDir("../../tsproto/src/main/kotlin")
        }
    }
}

val ktorVersion = "3.2.0"

dependencies {
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("ch.qos.logback:logback-classic:1.5.18")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("site.huixiang.web.GatewayKt")
}

tasks.test {
    useJUnitPlatform()
}
