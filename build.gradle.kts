// 플러그인은 여기서 한 번만 버전을 정하고(apply false), 각 모듈이 버전 없이 가져다 쓴다.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.kotlin.jpa) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
}

allprojects {
    group = "com.dochiri"
    version = "0.0.1-SNAPSHOT"
}

// 루트의 check 한 번으로 모든 모듈과 build-convention(복합 빌드)까지 검증한다.
tasks.register("check") {
    group = "verification"
    dependsOn(subprojects.map { "${it.path}:check" })
    dependsOn(gradle.includedBuild("build-convention").task(":check"))
}
