package com.dochiri.ilsim.runner.architecture

import com.dochiri.ilsim.runner.ConfigStore
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 규칙 모델은 등록된 프로젝트 id 별로 저장해 다른 프로젝트에 섞이지 않게 한다: `~/.ilsim/projects/<id>/architecture.json` */
class ArchitectureStore(config: ConfigStore) {
    private val base: Path = config.location.parent.resolve("projects")
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    private fun file(projectId: String): Path {
        require(Regex("""[A-Za-z0-9._-]+""").matches(projectId)) { "잘못된 프로젝트 id: $projectId" }
        return base.resolve(projectId).resolve("architecture.json")
    }

    fun save(projectId: String, model: ArchitectureModel): Path {
        val f = file(projectId)
        f.parent.createDirectories()
        f.writeText(json.encodeToString(model))
        return f
    }

    fun load(projectId: String): ArchitectureModel? =
        file(projectId).takeIf { it.exists() }?.let { json.decodeFromString<ArchitectureModel>(it.readText()) }
}
