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

tasks.register("check") {
    group = "verification"
    dependsOn(subprojects.map { "${it.path}:check" })
    dependsOn(gradle.includedBuild("build-convention").task(":check"))
}
