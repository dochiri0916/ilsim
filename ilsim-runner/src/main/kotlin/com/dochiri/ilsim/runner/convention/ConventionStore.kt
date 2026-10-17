package com.dochiri.ilsim.runner.convention

import com.dochiri.ilsim.runner.ConfigStore
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 컨벤션 모델도 프로젝트 id 별로 저장한다: `~/.ilsim/projects/<id>/convention.json` */
class ConventionStore(config: ConfigStore) {
    private val base: Path = config.location.parent.resolve("projects")
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    private fun file(projectId: String): Path {
        require(Regex("""[A-Za-z0-9._-]+""").matches(projectId)) { "잘못된 프로젝트 id: $projectId" }
        return base.resolve(projectId).resolve("convention.json")
    }

    fun save(projectId: String, model: ConventionModel): Path {
        val f = file(projectId)
        f.parent.createDirectories()
        f.writeText(json.encodeToString(model))
        return f
    }

    fun load(projectId: String): ConventionModel? =
        file(projectId).takeIf { it.exists() }?.let { json.decodeFromString<ConventionModel>(it.readText()) }
}
