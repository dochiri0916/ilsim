package com.dochiri.ilsim.runner

import java.nio.file.Files
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class UsageWindowReport(val utilization: Double, val resetsAt: Long? = null)

@Serializable
data class PlanUsageReport(
    val runnerId: String = "",
    val provider: String = "claude",
    val status: String? = null,
    val fiveHour: UsageWindowReport? = null,
    val sevenDay: UsageWindowReport? = null,
)

object ClaudePlanUsage {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

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

object PlanUsageWatch {
    private val latest = java.util.concurrent.ConcurrentHashMap<String, PlanUsageReport>()
    private val reported = mutableMapOf<String, PlanUsageReport>()
    private val probed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun observe(usage: PlanUsageReport) { latest[usage.provider] = usage }

    fun latest(provider: String): PlanUsageReport? = latest[provider]

    fun reportChanged(send: (PlanUsageReport) -> Unit) {
        for ((provider, now) in latest.toMap()) {
            if (now == reported[provider] && provider !in probed) continue
            send(now)
            reported[provider] = now
            probed.remove(provider)
        }
    }

    fun probe(): PlanUsageReport? {
        val dir = Files.createTempDirectory("ilsim-usage")
        try {
            val r = Shell.run(dir, "claude", "-p", "/usage", "--output-format", "json", timeoutMinutes = 2, maxChars = 200_000)
            check(r.exit == 0) { "claude /usage 실패(${r.exit}): ${r.output.takeLast(300)}" }
            return UsageCommand.parse(ClaudeCli.parse(r.output).text)?.copy(provider = ClaudeAccountScope.provider(ClaudeAccountScope.account))
                ?.also { observe(it); probed.add(it.provider) }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

}

class PlanUsageReporter(
    private val client: ServerClient,
    private val runnerId: String,
    private val interval: java.time.Duration = java.time.Duration.ofMinutes(5),
) {
    fun start(): Thread = Thread(::loop, "plan-usage").apply { isDaemon = true; start() }

    private fun syncAccounts() {
        runCatching { client.claudeEnvironment() }.onSuccess { env ->
            ClaudeAccounts.environment = env
            ClaudeAccounts.names().forEach(ClaudeAccounts::applyEnvironment)
        }.onFailure { println("Claude 설정 조회 실패: ${it.message}") }
        val accounts = ClaudeAccounts.refresh()
        runCatching { client.reportClaudeAccounts(runnerId, accounts) }.onSuccess { removed ->
            removed.forEach { println("Claude 계정 삭제: $it"); ClaudeAccounts.remove(it) }
        }.onFailure { println("Claude 계정 보고 실패: ${it.message}") }
    }

    private fun loop() {
        var next = java.time.Instant.MIN
        var nextModels = java.time.Instant.MIN
        while (true) {
            try {
                if (java.time.Instant.now() >= nextModels) {
                    nextModels = java.time.Instant.now().plus(java.time.Duration.ofHours(1))
                    ModelDiscovery.refresh().takeIf { it.isNotEmpty() }?.let { found ->
                        runCatching { client.reportModels(found) }.onFailure { println("모델 버전 보고 실패: ${it.message}") }
                    }
                }
                if (client.claimUsageRefresh(runnerId) || java.time.Instant.now() >= next) {
                    next = java.time.Instant.now().plus(interval)
                    syncAccounts()
                    (ClaudeAccounts.usable().ifEmpty { listOf(null) }).forEach { name ->
                        runCatching { ClaudeAccountScope.within(name) { PlanUsageWatch.probe() } }.onFailure { println("사용량 확인 실패(${name ?: "기본"}): ${it.message}") }
                    }
                }
                PlanUsageWatch.reportChanged { client.reportUsage(it.copy(runnerId = runnerId)) }
            } catch (e: Exception) {
            }
            Thread.sleep(3000)
        }
    }
}
