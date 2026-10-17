package com.dochiri.ilsim.runner.profile

import kotlinx.serialization.Serializable

@Serializable
data class Detected(
    val id: String,
    val label: String,
    val version: String? = null,
    val modules: List<String> = emptyList(),
    val evidence: List<String> = emptyList(),
)

@Serializable
data class ModuleInfo(
    val name: String,
    val path: String,
    val buildFile: String?,
    val mainFiles: Map<String, Int>,
    val testFiles: Int,
    val sourceRoots: List<String>,
    val springBootApplication: Boolean,
    val projectDependencies: List<String>,
)

@Serializable
data class JvmInfo(
    val kotlin: String? = null,
    val javaToolchain: Int? = null,
    val jvmTarget: Int? = null,
    val springBoot: String? = null,
    val gradleWrapper: String? = null,
)

@Serializable
data class ProjectProfile(
    val root: String,
    val name: String?,
    val vcs: String?,
    val buildTool: String,
    val primaryLanguage: String?,
    val languages: Map<String, Int>,
    val jvm: JvmInfo,
    val modules: List<ModuleInfo>,
    val frameworks: List<Detected>,
    val testing: List<Detected>,
    val analysisTools: List<Detected>,
    val configFiles: List<String>,
    val infrastructure: List<String>,
    val architectureTests: List<String>,
    val warnings: List<String>,
)
