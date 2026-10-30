import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The TeamSpeak connection itself: plain Kotlin on the JVM, no Android, so it's tested on the build machine.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
    val smokeServer = System.getenv("SHENGQIAO_TEST_SERVER") ?: ""
    inputs.property("shengQiaoTestServer", smokeServer)
    environment("SHENGQIAO_TEST_SERVER", smokeServer)
    val cloudTransfer = System.getenv("SHENGQIAO_ACCOUNT_TRANSFER") ?: ""
    inputs.property("cloudReadOnlyEnabled", cloudTransfer.isNotEmpty())
    environment("SHENGQIAO_ACCOUNT_TRANSFER", cloudTransfer)
    environment("TS_TEST_SERVER", System.getenv("TS_TEST_SERVER") ?: "")
    environment("TS_TEST_FT_PORT", System.getenv("TS_TEST_FT_PORT") ?: "")
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
