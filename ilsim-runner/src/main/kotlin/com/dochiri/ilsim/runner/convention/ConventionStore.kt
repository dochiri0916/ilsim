package com.dochiri.ilsim.runner.convention

import com.dochiri.ilsim.runner.ConfigStore
import com.dochiri.ilsim.runner.KnowledgeFiles
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
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

    fun save(projectId: String, model: ConventionModel): Path = update(projectId) { model }

    /** 잠금을 잡은 채 지금 저장된 모델을 [change] 에 넘기고 그 결과를 저장한다. */
    fun update(projectId: String, change: (ConventionModel?) -> ConventionModel): Path {
        val f = file(projectId)
        return KnowledgeFiles.locked(f.parent) {
            KnowledgeFiles.write(f, json.encodeToString(change(read(f))))
            f
        }
    }

    fun load(projectId: String): ConventionModel? = read(file(projectId))

    private fun read(f: Path): ConventionModel? = f.takeIf { it.exists() }?.let { json.decodeFromString<ConventionModel>(it.readText()) }
}
