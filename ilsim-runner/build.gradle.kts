plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

// 플러그인 접근자(kotlin {}, java {} 등)는 IDE 동기화 전에 해석되지 않을 수 있어, 확장을 타입으로 직접 설정한다.
extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

extensions.configure<JavaPluginExtension> {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    // TypeSafe JEV 클라이언트(code-risk-analyzer 와 같은 라이브러리). 키는 환경 변수 TYPESAFE_API_KEY 로 읽는다.
    implementation(libs.jev.typesafe)
    testImplementation(kotlin("test"))
}

extensions.configure<JavaApplication> {
    mainClass = "com.dochiri.ilsim.runner.MainKt"
}

tasks.withType<Test> {
    useJUnitPlatform()
}
