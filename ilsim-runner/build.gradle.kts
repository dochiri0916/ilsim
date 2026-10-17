plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    application
}

group = "com.dochiri"
version = "0.0.1-SNAPSHOT"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    // TypeSafe JEV 클라이언트(code-risk-analyzer 와 같은 라이브러리). 키는 환경 변수 TYPESAFE_API_KEY 로 읽는다.
    implementation("io.github.gudcks0305:jev-typesafe:0.2.0")
    testImplementation(kotlin("test"))
}

application {
    mainClass = "com.dochiri.ilsim.runner.MainKt"
}

tasks.test {
    useJUnitPlatform()
}
