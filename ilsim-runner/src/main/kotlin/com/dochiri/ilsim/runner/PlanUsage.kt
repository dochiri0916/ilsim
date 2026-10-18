package com.dochiri.ilsim.runner

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class UsageWindowReport(val utilization: Double, val resetsAt: Long? = null)

/** Claude 구독 플랜 사용량(5시간·7일). 서버로 보내 웹에 보여 준다. */
@Serializable
data class PlanUsageReport(
    val runnerId: String = "",
    val status: String? = null,
    val fiveHour: UsageWindowReport? = null,
    val sevenDay: UsageWindowReport? = null,
)

object ClaudePlanUsage {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** stream-json 한 줄이 `rate_limit_event` 면 사용량을 돌려준다. 창 정보가 하나도 없으면 null. */
    fun parse(line: String): PlanUsageReport? {
        if (!line.contains("\"rate_limit_event\"")) return null
        return try {
            val obj = json.parseToJsonElement(line) as? JsonObject ?: return null
            if (obj.text("type") != "rate_limit_event") return null
            val info = obj["rate_limit_info"] as? JsonObject ?: return null
            val windows = info["unifiedWindows"] as? JsonObject
            val fiveHour = (windows?.get("five_hour") as? JsonObject)?.window()
            val sevenDay = (windows?.get("seven_day") as? JsonObject)?.window()
            if (fiveHour == null && sevenDay == null) null else PlanUsageReport(status = info.text("status"), fiveHour = fiveHour, sevenDay = sevenDay)
        } catch (e: Exception) {
            null
        }
    }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.content

    private fun JsonObject.window(): UsageWindowReport? {
        val utilization = text("utilization")?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        return UsageWindowReport(utilization, text("resetsAt")?.toDoubleOrNull()?.toLong())
    }
}

/**
 * `claude -p /usage` 의 출력에서 사용량을 읽는다. 이 명령은 모델을 부르지 않아 토큰이 들지 않는다.
 * 예: `Current session: 28% used · resets Oct 18 at 2:20am (Asia/Seoul)`, `Current week (all models): 35% used · resets Oct 22 at 8pm (Asia/Seoul)`.
 * 사람이 읽는 문구라 바뀔 수 있으므로, 재설정 시각을 못 읽으면 사용률만 쓰고 사용률도 없으면 null.
 */
object UsageCommand {
    private val line = Regex("""(?m)^Current (session|week \(all models\)):\s*(\d+(?:\.\d+)?)% used(?:\s*·\s*resets\s+(.+?))?\s*$""")
    private val reset = Regex("""^(?:([A-Z][a-z]{2})\s+(\d{1,2})(?:\s+at)?\s+)?(\d{1,2})(?::(\d{2}))?\s*(am|pm)\s*(?:\(([^)]+)\))?$""", RegexOption.IGNORE_CASE)
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    fun parse(text: String, now: java.time.Instant = java.time.Instant.now()): PlanUsageReport? {
        var fiveHour: UsageWindowReport? = null
        var sevenDay: UsageWindowReport? = null
        for (m in line.findAll(text)) {
            val window = UsageWindowReport(m.groupValues[2].toDouble() / 100, m.groupValues[3].takeIf { it.isNotBlank() }?.let { resetsAt(it, now) })
            if (m.groupValues[1] == "session") fiveHour = window else sevenDay = window
        }
        return if (fiveHour == null && sevenDay == null) null else PlanUsageReport(fiveHour = fiveHour, sevenDay = sevenDay)
    }

    /** 연도가 없으므로 지금 이후 가장 가까운 시각으로 본다. 날짜가 없으면 오늘(지났으면 내일). */
    private fun resetsAt(text: String, now: java.time.Instant): Long? {
        val m = reset.matchEntire(text.trim()) ?: return null
        val (mon, day, hour, minute, ampm, zone) = m.destructured
        val zoneId = runCatching { java.time.ZoneId.of(zone) }.getOrNull() ?: java.time.ZoneId.systemDefault()
        val today = now.atZone(zoneId)
        val h = hour.toInt() % 12 + if (ampm.lowercase() == "pm") 12 else 0
        val time = java.time.LocalTime.of(h, minute.ifEmpty { "0" }.toInt())
        val candidate = if (mon.isEmpty()) {
            today.with(time).let { if (it.isBefore(today)) it.plusDays(1) else it }
        } else {
            val month = months.indexOf(mon.lowercase()).takeIf { it >= 0 } ?: return null
            val at = java.time.ZonedDateTime.of(java.time.LocalDate.of(today.year, month + 1, day.toInt()), time, zoneId)
            if (at.isBefore(today.minusDays(1))) at.plusYears(1) else at
        }
        return candidate.toEpochSecond()
    }
}

/** 작업 중 Claude 실행이 남긴 가장 최근 사용량과, 토큰 없이 읽는 `/usage` 조회. 서버 보고는 [PlanUsageReporter] 한 스레드만 한다. */
object PlanUsageWatch {
    private val latest = AtomicReference<PlanUsageReport?>(null)
    private var reported: PlanUsageReport? = null
    /** 직접 조회했으면 값이 같아도 보내 웹의 기준 시각을 갱신한다. */
    @Volatile private var probed = false

    fun observe(usage: PlanUsageReport) = latest.set(usage)

    /** 마지막으로 보낸 값과 다르면 [send] 로 보낸다. 보내다 실패하면 다음에 다시 보낸다. */
    fun reportChanged(send: (PlanUsageReport) -> Unit) {
        val now = latest.get() ?: return
        if (now == reported && !probed) return
        send(now)
        reported = now
        probed = false
    }

    /** `claude -p /usage` 로 읽는다. 모델을 부르지 않아 토큰이 들지 않는다. */
    fun probe(): PlanUsageReport? {
        val dir = Files.createTempDirectory("ilsim-usage")
        try {
            val r = Shell.run(dir, "claude", "-p", "/usage", "--output-format", "json", timeoutMinutes = 2, maxChars = 200_000)
            check(r.exit == 0) { "claude /usage 실패(${r.exit}): ${r.output.takeLast(300)}" }
            return UsageCommand.parse(ClaudeCli.parse(r.output).text)?.also { observe(it); probed = true }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}

/** 작업 실행과 따로 도는 사용량 스레드. [interval] 마다, 그리고 웹에서 "지금 확인"을 누르면 바로 읽어 서버로 보낸다. */
class PlanUsageReporter(
    private val client: ServerClient,
    private val runnerId: String,
    private val interval: java.time.Duration = java.time.Duration.ofMinutes(5),
) {
    fun start(): Thread = Thread(::loop, "plan-usage").apply { isDaemon = true; start() }

    private fun loop() {
        var next = java.time.Instant.MIN
        while (true) {
            try {
                if (client.claimUsageRefresh(runnerId) || java.time.Instant.now() >= next) {
                    next = java.time.Instant.now().plus(interval)
                    runCatching { PlanUsageWatch.probe() }.onFailure { println("사용량 확인 실패: ${it.message}") }
                }
                PlanUsageWatch.reportChanged { client.reportUsage(it.copy(runnerId = runnerId)) }
            } catch (e: Exception) {
                // 서버가 잠시 없어도 작업 루프와 무관하게 다시 시도한다.
            }
            Thread.sleep(3000)
        }
    }
}
