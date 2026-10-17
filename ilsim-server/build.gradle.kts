plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

extensions.configure<JavaPluginExtension> {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

// 플러그인 접근자(kotlin {}, java {} 등)는 IDE 동기화 전에 해석되지 않을 수 있어, 확장을 타입으로 직접 설정한다.
extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.withType<JavaCompile> {
    options.release = 21
}

repositories {
    mavenCentral()
}

extensions.configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:${libs.versions.spring.ai.get()}")
    }
}

dependencies {
    implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    // TypeSafe JEV 클라이언트(code-risk-analyzer 와 같은 라이브러리). 키는 환경 변수 TYPESAFE_API_KEY 로 읽는다.
    implementation(libs.jev.typesafe)
    implementation("org.springframework.security:spring-security-crypto")
    runtimeOnly("com.mysql:mysql-connector-j")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
