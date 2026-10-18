package com.dochiri.ilsim.runner

import com.dochiri.ilsim.runner.architecture.ArchitectureChecker
import com.dochiri.ilsim.runner.architecture.ArchitectureMiner
import com.dochiri.ilsim.runner.architecture.ArchitectureReport
import com.dochiri.ilsim.runner.architecture.ArchitectureReviewer
import com.dochiri.ilsim.runner.architecture.RuleApproval
import com.dochiri.ilsim.runner.architecture.ArchitectureStore
import com.dochiri.ilsim.runner.architecture.Applicability
import com.dochiri.ilsim.runner.convention.ConventionApproval
import com.dochiri.ilsim.runner.convention.ConventionChecker
import com.dochiri.ilsim.runner.convention.ConventionMiner
import com.dochiri.ilsim.runner.convention.ConventionReport
import com.dochiri.ilsim.runner.convention.ConventionReviewer
import com.dochiri.ilsim.runner.convention.ConventionStore
import com.dochiri.ilsim.runner.document.DocPurpose
import com.dochiri.ilsim.runner.document.DocumentContext
import com.dochiri.ilsim.runner.jev.Jev
import com.dochiri.ilsim.runner.jev.JevKey
import com.dochiri.ilsim.runner.jev.JevRiskScreen
import com.dochiri.ilsim.runner.memory.NoiseDetector
import com.dochiri.ilsim.runner.memory.ProjectMemory
import com.dochiri.ilsim.runner.risk.RiskReviewer
import com.dochiri.ilsim.runner.risk.RiskScanner
import com.dochiri.ilsim.runner.profile.Report
import com.dochiri.ilsim.runner.standards.ProjectStandards
import com.dochiri.ilsim.runner.standards.StandardsApplier
import com.dochiri.ilsim.runner.standards.StandardsCatalog
import com.dochiri.ilsim.runner.profile.StackDetector
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.system.exitProcess

private const val USAGE = """사용법:
  프로젝트
    ilsim-runner add <id> <path>                 프로젝트 등록
    ilsim-runner list                            등록된 프로젝트 보기
    ilsim-runner set <id> test|analysis|mutation <명령>   테스트·정적 분석·변이 테스트(PIT) 명령 지정
    ilsim-runner detect <path>                   기술 스택 감지
  규칙 만들기 (정적 분석 → AI 1회 검토 → 확실한 것은 자동 승인, 나머지는 사용자 선택)
    ilsim-runner architecture <id>               아키텍처 규칙
    ilsim-runner convention <id>                 코드 컨벤션 규칙
    ilsim-runner docs <id>                       규칙 검토에 쓰는 프로젝트 문서와 보는 기준
  규칙 관리
    ilsim-runner rules <id>                      규칙 상태(승인/거절/후보)와 AI 의견
    ilsim-runner approve <id> <규칙id...|--all>  승인
    ilsim-runner reject <id> <규칙id...>         거절 (이후 모든 검사에서 제외)
    ilsim-runner check <id>                      아키텍처·컨벤션 검사 (위반 시 종료 코드 1)
  위험 탐지 (트랜잭션·동시성·멱등성·복원력)
    ilsim-runner risk <id>                       탐지와 권고 (BLOCK 위반 시 종료 코드 1)
    ilsim-runner risk-dismiss <id> <규칙> [파일]  후보를 오탐으로 제외 (쌓이면 AI 도입 알림)
    ilsim-runner risk-ai <id> list|approve|revoke <규칙...>   규칙별 AI 검토 도입 승인
    ilsim-runner review-risk <id>                승인한 규칙의 후보를 AI가 검토
    ilsim-runner risk-set <id> <규칙> BLOCK|REVIEW|OFF   규칙 심각도
    ilsim-runner memory <id> collect|list|verify|approve|reject   검증된 학습 관리
  신규 프로젝트
    ilsim-runner standards                       기본 아키텍처·컨벤션 목록
    ilsim-runner apply-standards <id> [--architecture <표준> --root <패키지>] [--convention <표준>]
  JEV
    ilsim-runner jev                             JEV 연결 확인 (환경 변수 TYPESAFE_API_KEY 필요)
  실행
    ilsim-runner run                             서버에 연결해 작업 수행 (토큰: ILSIM_TOKEN 또는 서버가 만든 ~/.ilsim/token)"""

private const val AI_MODEL = ClaudeModels.REVIEW

fun main(args: Array<String>) {
    val store = ConfigStore()
    when (args.firstOrNull()) {
        "add" -> add(store, args)
        "list" -> store.load().projects.forEach { (id, p) ->
            println("$id\t${p.path}\ttest=${p.testCommand ?: "-"}\tanalysis=${p.analysisCommands.ifEmpty { listOf("-") }}")
        }
        "set" -> set(store, args)
        "detect" -> detect(args)
        "architecture" -> architecture(store, args)
        "convention" -> convention(store, args)
        "docs" -> docs(store, args)
        "rules" -> rulesCmd(store, args)
        "approve" -> decide(store, args, approve = true)
        "reject" -> decide(store, args, approve = false)
        "check" -> check(store, args)
        "risk" -> risk(store, args)
        "risk-dismiss" -> riskDismiss(store, args)
        "risk-ai" -> riskAi(store, args)
        "review-risk" -> reviewRisk(store, args)
        "risk-set" -> riskSet(store, args)
        "memory" -> memory(store, args)
        "standards" -> standards()
        "apply-standards" -> applyStandards(store, args)
        "jev" -> jevCheck()
        "run" -> run(store)
        else -> { println(USAGE); exitProcess(1) }
    }
}

private fun add(store: ConfigStore, args: Array<String>) {
    if (args.size != 3) { println(USAGE); exitProcess(1) }
    val path = Path.of(args[2]).toAbsolutePath().normalize()
    if (!path.isDirectory() || !path.resolve(".git").exists()) {
        println("git 저장소가 아닙니다: $path"); exitProcess(1)
    }
    val config = store.load()
    store.save(config.copy(projects = config.projects + (args[1] to ProjectEntry(path.toString(), args[1]))))
    println("등록: ${args[1]} -> $path (${store.location})")
}

private fun detect(args: Array<String>) {
    if (args.size < 2) { println(USAGE); exitProcess(1) }
    val profile = StackDetector.detect(Path.of(args[1]))
    if (args.contains("--json")) {
        println(Json { prettyPrint = true; encodeDefaults = true }.encodeToString(profile))
    } else print(Report.summary(profile))
}

private fun architecture(store: ConfigStore, args: Array<String>) {
    if (args.size < 2) { println(USAGE); exitProcess(1) }
    analyzeArchitecture(store, args[1], System.out)
}

/** [id] 가 등록된 프로젝트면 규칙을 저장하고 AI 검토까지 한다. 등록되지 않았으면 경로로 보고 분석만 한다. 출력은 [out] 으로 보낸다. */
fun analyzeArchitecture(store: ConfigStore, id: String, out: Appendable) {
    val registered = store.load().projects[id]
    val path = Path.of(registered?.path ?: id).toAbsolutePath().normalize()
    val profile = StackDetector.detect(path)
    val archStore = ArchitectureStore(store)
    // 다시 분석해도 사용자가 내린 승인·거절과 AI 의견은 같은 규칙에 유지한다.
    var model = RuleApproval.carryOver(registered?.let { archStore.load(id) }, ArchitectureMiner.mine(path, profile))
    val self = if (model.rules.isEmpty()) null else ArchitectureChecker.check(path, profile, model)
    if (registered != null) {
        out.appendLine("저장: ${archStore.save(id, model)} (프로젝트 ${id} 전용)")
    } else out.appendLine("등록되지 않은 경로라 저장하지 않았습니다 (먼저 add 로 등록해야 규칙이 프로젝트 전용으로 저장됩니다)")
    out.append(ArchitectureReport.summary(model, self))
    if (registered != null && model.rules.isNotEmpty()) {
        // 정적으로 만든 규칙을 AI가 한 번 검토하고, 확실한 것만 승인한다. 애매한 것은 사용자에게 넘긴다.
        val cost = CostTally()
        try {
            val docs = DocumentContext.build(path, DocPurpose.ARCHITECTURE)
            if (!docs.isEmpty) out.appendLine("\n참고 문서(${docs.purpose.label}): ${docs.refs.joinToString { it.path }}")
            model = ArchitectureReviewer.review(model, AI_MODEL, docs = docs) { cost.ask(it, AI_MODEL) }
            model = RuleApproval.autoApprove(model)
            val reviewed = model
            archStore.update(id) { saved -> RuleApproval.keepUserDecisions(saved, reviewed).also { model = it } }
            out.appendLine()
            out.append(ArchitectureReport.triage(model, id))
        } catch (e: Exception) {
            out.appendLine("AI 검토를 건너뜀: ${e.message} (규칙은 후보 상태로 저장됨)")
        }
        cost.summary()?.let { out.appendLine(it) }
    }
}

private fun requireProject(store: ConfigStore, args: Array<String>): String {
    val id = args.getOrNull(1)
    if (id == null || store.load().projects[id] == null) { println(USAGE); exitProcess(1) }
    return id
}

private fun check(store: ConfigStore, args: Array<String>) {
    val id = requireProject(store, args)
    val path = Path.of(store.load().projects.getValue(id).path)
    val profile = StackDetector.detect(path)
    var failed = false

    println("[아키텍처]")
    val arch = ArchitectureStore(store).load(id)
    when {
        arch == null -> println("  규칙 없음 (architecture $id 를 먼저 실행)")
        arch.applicability.status == Applicability.NOT_APPLICABLE -> {
            println("  SKIPPED: 아키텍처 검증 대상이 아닌 프로젝트입니다")
            arch.applicability.reasons.forEach { println("    - $it") }
        }
        else -> try {
            val v = ArchitectureChecker.check(path, profile, arch)
            print(ArchitectureReport.violations(v).prependIndent("  "))
            if (v.isNotEmpty()) failed = true
        } catch (e: IllegalArgumentException) {
            println("  ${e.message}")
        }
    }

    println("[코드 컨벤션]")
    val conv = ConventionStore(store).load(id)
    if (conv == null) println("  규칙 없음 (convention $id 를 먼저 실행)")
    else {
        val v = ConventionChecker.check(path, profile, conv)
        print(ArchitectureReport.violations(v).prependIndent("  "))
        if (v.isNotEmpty()) failed = true
    }
    if (failed) exitProcess(1)
}

private fun rulesCmd(store: ConfigStore, args: Array<String>) {
    val id = requireProject(store, args)
    val arch = ArchitectureStore(store).load(id)
    val conv = ConventionStore(store).load(id)
    if (arch == null && conv == null) { println("저장된 규칙이 없습니다. architecture $id, convention $id 를 먼저 실행하세요"); exitProcess(2) }
    arch?.let { println("[아키텍처]"); print(ArchitectureReport.rules(it)) }
    conv?.let { println("[코드 컨벤션]"); print(ConventionReport.rules(it)) }
}

/** 규칙 id 는 아키텍처와 컨벤션을 가리지 않고 받는다. 어느 쪽 규칙인지는 id 로 찾는다. */
private fun decide(store: ConfigStore, args: Array<String>, approve: Boolean) {
    val id = requireProject(store, args)
    val targets = args.drop(2)
    if (targets.isEmpty() || (!approve && targets == listOf("--all"))) { println(USAGE); exitProcess(1) }
    val archStore = ArchitectureStore(store)
    val convStore = ConventionStore(store)
    val arch = archStore.load(id)
    val conv = convStore.load(id)
    val all = approve && targets == listOf("--all")
    val archIds = if (all) arch?.let { RuleApproval.undecidedIds(it) }.orEmpty() else targets.filter { t -> arch?.rules?.any { it.id == t } == true }
    val convIds = if (all) conv?.let { ConventionApproval.undecidedIds(it) }.orEmpty() else targets.filter { t -> conv?.rules?.any { it.id == t } == true }
    val unknown = if (all) emptyList() else targets.filter { it !in archIds && it !in convIds }
    if (unknown.isNotEmpty()) { println("알 수 없는 규칙 id: ${unknown.joinToString()}"); exitProcess(1) }
    if (arch != null && archIds.isNotEmpty()) archStore.save(id, if (approve) RuleApproval.approve(arch, archIds) else RuleApproval.reject(arch, archIds))
    if (conv != null && convIds.isNotEmpty()) convStore.save(id, if (approve) ConventionApproval.approve(conv, convIds) else ConventionApproval.reject(conv, convIds))
    println("${if (approve) "승인" else "거절"}: 아키텍처 ${archIds.size}개 · 컨벤션 ${convIds.size}개")
}

private fun docs(store: ConfigStore, args: Array<String>) {
    if (args.size < 2) { println(USAGE); exitProcess(1) }
    val path = Path.of(store.load().projects[args[1]]?.path ?: args[1]).toAbsolutePath().normalize()
    val purposes = when (args.getOrNull(2)) {
        null -> DocPurpose.values().toList()
        else -> listOf(DocPurpose.values().firstOrNull { it.name.equals(args[2], ignoreCase = true) } ?: run { println(USAGE); exitProcess(1) })
    }
    for (purpose in purposes) {
        val ctx = DocumentContext.build(path, purpose)
        println("[${purpose.label}] 보는 기준: ${purpose.criteria}")
        if (ctx.isEmpty) println("  사용할 문서가 없습니다") else ctx.refs.forEach { println("  - ${it.path} (점수 ${it.score}: ${it.reason})") }
    }
}

private fun risk(store: ConfigStore, args: Array<String>) {
    if (args.size < 2) { println(USAGE); exitProcess(1) }
    val registered = store.load().projects[args[1]]
    val path = Path.of(registered?.path ?: args[1]).toAbsolutePath().normalize()
    val config = registered?.let { RiskScanner.Store(store).load(args[1]) } ?: com.dochiri.ilsim.runner.risk.RiskConfig()
    val findings = RiskScanner.scan(path, StackDetector.detect(path), config) ?: run { println("분석할 Java·Kotlin main 소스가 없습니다"); exitProcess(2) }
    val opinions = registered?.let { RiskReviewer.Store(store).load(args[1]) }.orEmpty()
    val verdict = { v: com.dochiri.ilsim.runner.architecture.Violation -> opinions[RiskReviewer.fingerprint(path, v)] }
    val suppressed = registered?.let { ProjectMemory(store).suppressed(args[1]) }.orEmpty()
    val shown = findings.filter { RiskReviewer.fingerprint(path, it) !in suppressed }
    println("규칙(심각도):")
    println(RiskScanner.describe(config))
    println()
    print(ArchitectureReport.violations(shown))
    shown.forEach { v ->
        verdict(v)?.let { o -> println("      AI:${o.verdict} ${o.reason}${if (o.recommendation.isBlank()) "" else " → ${o.recommendation}"}") }
    }
    val hidden = findings.size - shown.size
    if (hidden > 0) println("승인된 메모리로 오탐 확정된 후보 ${hidden}건은 숨겼습니다 (memory ${args[1]} list)")
    val pending = shown.count { verdict(it)?.verdict == "FALSE" }
    if (pending > 0) println("AI가 오탐으로 본 ${pending}건은 아직 승인 전이라 표시합니다: memory ${args[1]} collect 후 approve")
    if (shown.isNotEmpty()) { println(); println("권고:"); println(RiskScanner.remedies(shown)) }
    registered?.let {
        NoiseDetector.proposals(config, shown, ProjectMemory(store).load(args[1])).forEach { p -> println(); println("알림 " + NoiseDetector.message(args[1], p)) }
    }
    if (shown.any { it.severity == "BLOCK" }) exitProcess(1)
}

private fun reviewRisk(store: ConfigStore, args: Array<String>) {
    val project = args.getOrNull(1)?.let { store.load().projects[it] } ?: run { println(USAGE); exitProcess(1) }
    val path = Path.of(project.path)
    val config = RiskScanner.Store(store).load(args[1])
    val findings = RiskScanner.scan(path, StackDetector.detect(path), config) ?: run { println("분석할 Java·Kotlin main 소스가 없습니다"); exitProcess(2) }
    val opinionStore = RiskReviewer.Store(store)
    if (config.aiReview.isEmpty()) {
        println("AI 검토를 승인한 규칙이 없습니다. 정적 검사로 먼저 처리하고, 오탐이 쌓이면 알림이 뜹니다 (risk-ai ${args[1]} approve <규칙>)")
        exitProcess(0)
    }
    val targets = findings.filter { it.ruleId in config.aiReview }
    val cost = CostTally()
    try {
        val known = opinionStore.load(args[1])
        val updated = RiskReviewer.review(path, targets, known, AI_MODEL, false, JevRiskScreen.of(Jev.fromEnvironment(), path)) { cost.ask(it, AI_MODEL) }
        opinionStore.save(args[1], updated)
        val reviewed = targets.filter { it.severity == "REVIEW" }.mapNotNull { updated[RiskReviewer.fingerprint(path, it)] }
        println("검토: 승인한 규칙(${config.aiReview.sorted().joinToString()})의 후보 ${targets.count { it.severity == "REVIEW" }}건 중 ${reviewed.size}건에 의견 (실제 위험 ${reviewed.count { it.verdict == "TRUE" }}, 오탐 ${reviewed.count { it.verdict == "FALSE" }}, 불확실 ${reviewed.count { it.verdict == "UNSURE" }})")
        println("자세한 내용: risk ${args[1]}")
        cost.summary()?.let(::println)
    } catch (e: IllegalArgumentException) {
        println(e.message); exitProcess(1)
    } catch (e: IllegalStateException) {
        println(e.message); exitProcess(1)
    }
}

private fun memory(store: ConfigStore, args: Array<String>) {
    val projectId = args.getOrNull(1)
    val project = projectId?.let { store.load().projects[it] } ?: run { println(USAGE); exitProcess(1) }
    val path = Path.of(project.path)
    val memory = ProjectMemory(store)
    val sub = args.getOrNull(2) ?: run { println(USAGE); exitProcess(1) }
    fun findings() = RiskScanner.scan(path, StackDetector.detect(path), RiskScanner.Store(store).load(projectId))
        ?: run { println("분석할 Java·Kotlin main 소스가 없습니다"); exitProcess(2) }
    try {
        when (sub) {
            "collect" -> {
                val commit = Shell.git(path, "rev-parse", "HEAD").takeIf { it.exit == 0 }?.output?.trim()
                val n = memory.collect(projectId, path, findings(), RiskReviewer.Store(store).load(projectId), commit)
                println("후보 등록: ${n}건 (UNSURE 는 등록하지 않습니다)")
            }
            "verify" -> println("근거가 사라져 폐기: ${memory.verify(projectId, path, findings())}건")
            "approve" -> {
                val targets = args.drop(3)
                memory.approve(projectId, if (targets == listOf("--all")) memory.pendingIds(projectId) else targets)
            }
            "reject" -> memory.reject(projectId, args.drop(3))
            "list" -> Unit
            else -> { println(USAGE); exitProcess(1) }
        }
    } catch (e: IllegalArgumentException) {
        println(e.message); exitProcess(1)
    }
    print(memory.report(memory.load(projectId)))
}

private fun riskDismiss(store: ConfigStore, args: Array<String>) {
    val project = args.getOrNull(1)?.let { store.load().projects[it] }
    val rule = args.getOrNull(2)
    if (project == null || rule == null) { println(USAGE); exitProcess(1) }
    val path = Path.of(project.path)
    val config = RiskScanner.Store(store).load(args[1])
    val findings = RiskScanner.scan(path, StackDetector.detect(path), config) ?: run { println("분석할 Java·Kotlin main 소스가 없습니다"); exitProcess(2) }
    val filter = args.getOrNull(3)
    val commit = Shell.git(path, "rev-parse", "HEAD").takeIf { it.exit == 0 }?.output?.trim()
    val memory = ProjectMemory(store)
    val n = memory.dismiss(args[1], path, findings, commit) { it.ruleId == rule && (filter == null || filter in it.file) }
    println("오탐으로 제외: ${n}건 (메모리에 활성으로 기록, memory ${args[1]} list)")
    val remaining = findings.filter { RiskReviewer.fingerprint(path, it) !in memory.suppressed(args[1]) }
    NoiseDetector.proposals(config, remaining, memory.load(args[1])).forEach { println("알림 " + NoiseDetector.message(args[1], it)) }
}

private fun riskAi(store: ConfigStore, args: Array<String>) {
    val id = args.getOrNull(1)
    if (id == null || store.load().projects[id] == null) { println(USAGE); exitProcess(1) }
    val riskStore = RiskScanner.Store(store)
    try {
        when (args.getOrNull(2)) {
            "approve" -> riskStore.setAi(id, args.drop(3), true)
            "revoke" -> riskStore.setAi(id, args.drop(3), false)
            "list" -> Unit
            else -> { println(USAGE); exitProcess(1) }
        }
    } catch (e: IllegalArgumentException) {
        println(e.message); exitProcess(1)
    }
    val config = riskStore.load(id)
    println("AI 검토 승인 규칙: ${config.aiReview.sorted().ifEmpty { listOf("(없음)") }.joinToString()}")
}

private fun riskSet(store: ConfigStore, args: Array<String>) {
    if (args.size != 4 || store.load().projects[args[1]] == null) { println(USAGE); exitProcess(1) }
    try {
        RiskScanner.Store(store).set(args[1], args[2], args[3])
        println("설정: ${args[1]} ${args[2]} = ${args[3]}")
    } catch (e: IllegalArgumentException) {
        println(e.message); exitProcess(1)
    }
}

private fun convention(store: ConfigStore, args: Array<String>) {
    if (args.size < 2) { println(USAGE); exitProcess(1) }
    analyzeConvention(store, args[1], System.out)
}

/** [id] 가 등록된 프로젝트면 규칙을 저장하고 AI 검토까지 한다. 등록되지 않았으면 경로로 보고 분석만 한다. 출력은 [out] 으로 보낸다. */
fun analyzeConvention(store: ConfigStore, id: String, out: Appendable) {
    val registered = store.load().projects[id]
    val path = Path.of(registered?.path ?: id).toAbsolutePath().normalize()
    val profile = StackDetector.detect(path)
    val previous = registered?.let { ConventionStore(store).load(id) }
    val mined = ConventionMiner.mine(path, profile)
    // 다시 분석해도 사용자가 내린 승인·거절과 AI 의견은 같은 규칙에 유지한다.
    var model = ConventionApproval.carryOver(previous, mined)
    val self = if (model.rules.isEmpty()) null else ConventionChecker.check(path, profile, model)
    if (registered != null) {
        out.appendLine("저장: ${ConventionStore(store).save(id, model)} (프로젝트 ${id} 전용)")
    } else out.appendLine("등록되지 않은 경로라 저장하지 않았습니다 (먼저 add 로 등록해야 규칙이 프로젝트 전용으로 저장됩니다)")
    out.append(ConventionReport.summary(model, self))
    if (registered != null && model.rules.isNotEmpty()) {
        val cost = CostTally()
        try {
            val docs = DocumentContext.build(path, DocPurpose.CONVENTION)
            if (!docs.isEmpty) out.appendLine("\n참고 문서(${docs.purpose.label}): ${docs.refs.joinToString { it.path }}")
            model = ConventionReviewer.review(model, AI_MODEL, docs = docs) { cost.ask(it, AI_MODEL) }
            model = ConventionApproval.autoApprove(model)
            val reviewed = model
            ConventionStore(store).update(id) { saved -> ConventionApproval.keepUserDecisions(saved, reviewed).also { model = it } }
            out.appendLine()
            out.append(ConventionReport.triage(model, id))
        } catch (e: Exception) {
            out.appendLine("AI 검토를 건너뜀: ${e.message} (규칙은 후보 상태로 저장됨)")
        }
        cost.summary()?.let { out.appendLine(it) }
    }
}

private fun jevCheck() {
    val jev = Jev.fromEnvironment()
    if (!jev.enabled) {
        println("JEV 키가 없습니다. 환경 변수 TYPESAFE_API_KEY 를 설정하세요 (JEV_API_KEY 도 가능). 키는 파일이나 명령 인자에 넣지 않습니다.")
        exitProcess(1)
    }
    val p = jev.probability(mapOf("text" to "The build passed with exit code 0."), io.github.gudcks0305.jev.NoulQuestion.of("passed", "Did the build succeed?"))
    if (p == null) { println("JEV 호출에 실패했습니다. 키와 네트워크를 확인하세요"); exitProcess(1) }
    println("JEV 연결 확인: 응답 확률 ${"%.2f".format(p)} (1에 가까울수록 정상)")
}

private fun standards() {
    val catalog = StandardsCatalog()
    println("기본 아키텍처:")
    if (catalog.architectures.isEmpty()) println("  (없음)") else catalog.architectures.values.forEach { println("  ${it.id}\t${it.description}") }
    println("기본 컨벤션:")
    if (catalog.conventions.isEmpty()) println("  (없음)") else catalog.conventions.values.forEach { println("  ${it.id}\t${it.description}") }
}

private fun applyStandards(store: ConfigStore, args: Array<String>) {
    if (args.size < 3) { println(USAGE); exitProcess(1) }
    val project = store.load().projects[args[1]] ?: run { println("등록되지 않은 프로젝트: ${args[1]}"); exitProcess(1) }
    fun option(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val selection = ProjectStandards(option("--architecture"), option("--convention"), option("--root"))
    if (selection.architectureId == null && selection.conventionId == null) { println(USAGE); exitProcess(1) }
    try {
        val path = Path.of(project.path)
        StandardsApplier(store).apply(args[1], project.name, StackDetector.detect(path), selection)
        println("적용: ${args[1]} architecture=${selection.architectureId ?: "-"} convention=${selection.conventionId ?: "-"}")
    } catch (e: IllegalArgumentException) {
        println(e.message); exitProcess(1)
    }
}

private fun set(store: ConfigStore, args: Array<String>) {
    if (args.size < 4) { println(USAGE); exitProcess(1) }
    val config = store.load()
    val project = config.projects[args[1]] ?: run { println("등록되지 않은 프로젝트: ${args[1]}"); exitProcess(1) }
    val command = args.drop(3).joinToString(" ")
    val updated = when (args[2]) {
        "test" -> project.copy(testCommand = command)
        "analysis" -> project.copy(analysisCommands = project.analysisCommands + command)
        "mutation" -> project.copy(mutationCommand = command)
        else -> { println(USAGE); exitProcess(1) }
    }
    store.save(config.copy(projects = config.projects + (args[1] to updated)))
    println("설정: ${args[1]} ${args[2]} = $command")
}

/** 웹의 분석 버튼: 아키텍처와 컨벤션 규칙을 차례로 다시 뽑는다. 앞이 실패해도 뒤는 돌린다. */
private fun analyzeProject(store: ConfigStore, projectId: String): AnalysisResult {
    val out = StringBuilder()
    val errors = mutableListOf<String>()
    for ((label, analyze) in listOf<Pair<String, (ConfigStore, String, Appendable) -> Unit>>(
        "아키텍처" to ::analyzeArchitecture,
        "컨벤션" to ::analyzeConvention,
    )) {
        out.appendLine("[$label]")
        try {
            analyze(store, projectId, out)
        } catch (e: Exception) {
            errors += "$label 분석 실패: ${e.message}"
            out.appendLine(errors.last())
        }
        out.appendLine()
    }
    return AnalysisResult(errors.isEmpty(), out.toString().trimEnd(), errors.joinToString("\n").ifEmpty { null })
}

private fun run(store: ConfigStore) {
    val tokenFile = store.location.parent.resolve("token")
    val token = RunnerToken.resolve(System.getenv("ILSIM_TOKEN"), tokenFile)
        ?: run { println("토큰이 없습니다. 서버를 먼저 실행해 ${tokenFile} 을 만들거나 ILSIM_TOKEN 환경변수를 설정하세요"); exitProcess(1) }
    val config = store.load()
    val client = ServerClient(config.serverUrl, token)
    val executor = TaskExecutor(config, client)
    val report = config.projects.map { (id, p) -> ProjectReport(id, p.name) }
    println("runner ${config.runnerId} -> ${config.serverUrl}, 프로젝트 ${report.map { it.id }}")
    PlanUsageReporter(client, config.runnerId).start()
    while (true) {
        try {
            client.claimAnalysis(AnalysisClaim(config.runnerId, report.map { it.id }))?.let { projectId ->
                println("분석 수신: $projectId")
                client.analysisResult(projectId, analyzeProject(store, projectId))
                println("분석 종료: $projectId")
                continue
            }
            val task = client.poll(PollRequest(config.runnerId, report))
            if (task != null) {
                println("작업 수신: ${task.taskId} (${task.projectId})")
                JevKey.fromServer = runCatching { client.jevKey() }.getOrNull()
                executor.run(task)
                println("작업 종료: ${task.taskId}")
            } else Thread.sleep(2000)
        } catch (e: Exception) {
            println("연결 오류: ${e.message}")
            Thread.sleep(5000)
        }
    }
}
