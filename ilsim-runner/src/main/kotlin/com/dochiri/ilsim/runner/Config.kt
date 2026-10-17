package com.dochiri.ilsim.runner

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ProjectEntry(
    val path: String,
    val name: String,
    /** 테스트 실행 명령. 없으면 테스트 검증은 UNKNOWN으로 기록된다. */
    val testCommand: String? = null,
    /** 정적 분석 명령들. 코드가 바뀌는 단계마다 모두 실행된다. 없으면 UNKNOWN. */
    val analysisCommands: List<String> = emptyList(),
    /** 변이 테스트(PIT) 실행 명령. 구현 단계에서 테스트가 통과한 뒤 실행하며, 없으면 UNKNOWN. */
    val mutationCommand: String? = null,
)

@Serializable
data class RunnerConfig(
    val runnerId: String = "runner-" + java.util.UUID.randomUUID().toString().take(8),
    val serverUrl: String = "http://localhost:8080",
    val projects: Map<String, ProjectEntry> = emptyMap(),
)

/** `~/.ilsim/runner.json` — 프로젝트 경로는 이 파일만 소유하고 서버로 보내지 않는다. */
class ConfigStore(
    private val file: Path = Path.of(System.getProperty("user.home"), ".ilsim", "runner.json"),
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun load(): RunnerConfig =
        if (file.exists()) json.decodeFromString(file.readText()) else RunnerConfig().also(::save)

    fun save(config: RunnerConfig) {
        file.parent.createDirectories()
        file.writeText(json.encodeToString(config))
    }

    val location: Path get() = file
}

/**
 * 서버에 보내는 토큰. 환경 변수 ILSIM_TOKEN 이 있으면 그 값, 없으면 서버가 만든 `~/.ilsim/token` 파일.
 * 같은 PC 에서 서버를 먼저 띄우면 파일이 생기므로 따로 설정하지 않아도 된다.
 */
object RunnerToken {
    fun resolve(env: String?, file: Path): String? =
        env?.takeIf { it.isNotBlank() } ?: file.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
}
