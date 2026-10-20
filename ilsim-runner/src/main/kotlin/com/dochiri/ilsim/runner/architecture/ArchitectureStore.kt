package com.dochiri.ilsim.runner.architecture

import com.dochiri.ilsim.runner.ConfigStore
import com.dochiri.ilsim.runner.KnowledgeFiles
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ArchitectureStore(config: ConfigStore) {
    private val base: Path = config.location.parent.resolve("projects")
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    private fun file(projectId: String): Path {
        require(Regex("""[A-Za-z0-9._-]+""").matches(projectId)) { "잘못된 프로젝트 id: $projectId" }
        return base.resolve(projectId).resolve("architecture.json")
    }

    fun save(projectId: String, model: ArchitectureModel): Path = update(projectId) { model }

    fun update(projectId: String, change: (ArchitectureModel?) -> ArchitectureModel): Path {
        val f = file(projectId)
        return KnowledgeFiles.locked(f.parent) {
            KnowledgeFiles.write(f, json.encodeToString(change(read(f))))
            f
        }
    }

    fun load(projectId: String): ArchitectureModel? = read(file(projectId))

    private fun read(f: Path): ArchitectureModel? = f.takeIf { it.exists() }?.let { json.decodeFromString<ArchitectureModel>(it.readText()) }
}
