plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.dochiri"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:2.0.1")
    }
}

dependencies {
    implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")
    // TypeSafe JEV 클라이언트(code-risk-analyzer 와 같은 라이브러리). 키는 환경 변수 TYPESAFE_API_KEY 로 읽는다.
    implementation("io.github.gudcks0305:jev-typesafe:0.2.0")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    runtimeOnly("com.mysql:mysql-connector-j")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
