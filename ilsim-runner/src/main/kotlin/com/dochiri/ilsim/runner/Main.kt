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
import com.dochiri.ilsim.runner.document.ProjectDocs
import com.dochiri.ilsim.runner.jev.Guidelines
import com.dochiri.ilsim.runner.jev.Jev
import com.dochiri.ilsim.runner.jev.JevRequirementEval
import com.dochiri.ilsim.runner.jev.JevRiskEval
import com.dochiri.ilsim.runner.mcp.ActiveTasks
import com.dochiri.ilsim.runner.mcp.Hooks
import com.dochiri.ilsim.runner.mcp.IlsimMcp
import com.dochiri.ilsim.runner.mcp.McpServer
import com.dochiri.ilsim.runner.mcp.TaskGuide
import com.dochiri.ilsim.runner.jev.JevKey
import com.dochiri.ilsim.runner.jev.JevRiskScreen
import com.dochiri.ilsim.runner.memory.NoiseDetector
import com.dochiri.ilsim.runner.memory.ProjectMemory
import com.dochiri.ilsim.runner.risk.RiskReviewer
import com.dochiri.ilsim.runner.risk.RiskScanner
import com.dochiri.ilsim.runner.profile.Report
import com.dochiri.ilsim.runner.standards.ArchitectureRecommender
import com.dochiri.ilsim.runner.standards.ConventionRecommender
import com.dochiri.ilsim.runner.standards.ProjectStandards
import com.dochiri.ilsim.runner.standards.RecommendationReport
import com.dochiri.ilsim.runner.standards.Recommendations
import com.dochiri.ilsim.runner.standards.StandardsApplier
import com.dochiri.ilsim.runner.standards.StandardsCatalog
import com.dochiri.ilsim.runner.profile.StackDetector
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.isDirectory
import kotlin.io.path.writeText
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
    ilsim-runner docs <id>                       이 프로젝트의 지침 문서 목록과 규칙 검토에 쓰는 문서
    ilsim-runner docs add <id> <경로>            지침 문서(파일·폴더, 저장소 밖 가능)를 이 프로젝트에 추가
    ilsim-runner docs exclude <id> <경로>        지침 문서에서 뺄 경로
    ilsim-runner docs remove <id> <경로>         추가·제외 목록에서 경로를 지움
    ilsim-runner guidelines <id> "<요청>" [기준]  JEV가 고르는 지침과 점수, 기준(Git ref)을 주면 작업 트리 diff의 위반 판정
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
    ilsim-runner recommend <id|경로>             헥사고날·DDD·클린 아키텍처·객체지향·생활 체조 기준 추천 규칙(정적, AI 없음)
    ilsim-runner apply-standards <id> [--architecture <표준> --root <패키지>] [--convention <표준>]
  시험용 프로젝트 모음(채굴 회귀 확인, AI 없음)
    ilsim-runner corpus add <id> <기준 폴더 기준 상대경로>   프로젝트 추가(현재 HEAD 고정)
    ilsim-runner corpus update [id...]           현재 채굴 결과를 기준으로 저장
    ilsim-runner corpus check [id...]            기준과 비교(변화·라벨 문제·커밋 불일치 시 종료 코드 1)
    ilsim-runner corpus label <id> correct|wrong|expect <규칙id> [메모...]   정답 라벨(expect는 id 접두사로 기대한 규칙)
    ilsim-runner corpus export <id> <등록한 프로젝트 id>   Claude 앱·CLI에서 사용자가 승인/거절한 규칙을 라벨로 가져오기
                                                 옵션: --base <폴더>(기본 ILSIM_CORPUS_BASE 또는 ~/programming), --data <폴더>(기본 ilsim-runner/corpus)
  JEV
    ilsim-runner jev                            JEV 연결 확인 (환경 변수 TYPESAFE_API_KEY 필요)
    ilsim-runner jev-key set|delete|status      서버에 JEV 키 등록·삭제·상태 (set 은 키를 표준 입력으로 받음, 명령 인자에 넣지 않음)
    ilsim-runner jev-eval [--data <폴더>] [--repeat <n>] [--agent] [--out <파일>]   주입형 사례로 JEV 위험 선별 평가 (코드 발췌가 TypeSafe로 전송, --agent는 Claude 비교)
    ilsim-runner jev-eval --project <경로>... [--limit <n>] [--agent]   실제 코드 후보에서 판단이 갈린 것만 추림 (라벨 없음)
    ilsim-runner jev-eval requirements [--data <폴더>] [--repeat <n>] [--agent] [--out <파일>]   주입형 diff로 요구사항 충족 판정 평가
  Claude 앱
    ilsim-runner mcp                             일심 MCP 서버(stdio). Claude 앱 플러그인이 실행한다
    ilsim-runner hook user-prompt-submit|pre-tool-use|stop   Claude 앱 훅(표준 입력으로 훅 JSON)
    ilsim-runner hook commit                     Git pre-commit 훅(start_task가 설치, Agent의 직접 커밋을 막음)
"""

private val AI_MODEL: String get() = ClaudeModels.REVIEW

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
        "guidelines" -> guidelines(store, args)
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
        "recommend" -> recommend(store, args)
        "apply-standards" -> applyStandards(store, args)
        "corpus" -> corpus(args)
        "jev" -> jevCheck()
        "mcp" -> mcp(store)
        "jev-key" -> jevKey(store, args)
        "hook" -> hook(store, args)
        "jev-eval" -> jevEval(args)
        else -> { println(USAGE); exitProcess(1) }
    }
}

private fun corpus(args: Array<String>) {
    val rest = args.drop(1)
    fun option(name: String) = rest.zipWithNext().firstOrNull { it.first == name }?.second
    val positional = rest.filterIndexed { i, a -> !a.startsWith("--") && rest.getOrNull(i - 1)?.startsWith("--") != true }
    val base = Path.of(option("--base") ?: System.getenv("ILSIM_CORPUS_BASE") ?: "${System.getProperty("user.home")}/programming").toAbsolutePath().normalize()
    val data = Path.of(option("--data") ?: "ilsim-runner/corpus").toAbsolutePath().normalize()
    val corpus = com.dochiri.ilsim.runner.corpus.Corpus(data, base)
    when (positional.firstOrNull()) {
        "add" -> {
            if (positional.size != 3) { println(USAGE); exitProcess(1) }
            val e = corpus.add(positional[1], positional[2])
            println("추가: ${e.id} ${e.path} (${e.commit.take(7)}). 기준을 저장하려면 corpus update ${e.id}")
        }
        "update" -> corpus.update(positional.drop(1)).forEach(::println)
        "label" -> {
            if (positional.size < 4) { println(USAGE); exitProcess(1) }
            val labels = corpus.addLabels(positional[1], positional[2], listOf(com.dochiri.ilsim.runner.corpus.Label(positional[3], positional.drop(4).joinToString(" "))))
            println("라벨: 맞다 ${labels.correct.size} · 틀리다 ${labels.wrong.size} · 기대 ${labels.expect.size}")
        }
        "export" -> {
            if (positional.size != 3) { println(USAGE); exitProcess(1) }
            val store = ConfigStore()
            val arch = ArchitectureStore(store).load(positional[2])?.rules.orEmpty().map { Triple(it.id, it.decidedBy, it.approved to it.rejected) }
            val conv = ConventionStore(store).load(positional[2])?.rules.orEmpty().map { Triple(it.id, it.decidedBy, it.approved to it.rejected) }
            val user = (arch + conv).filter { it.second == "user" && !Recommendations.isRecommended(it.first) }
            corpus.addLabels(positional[1], "correct", user.filter { it.third.first }.map { com.dochiri.ilsim.runner.corpus.Label(it.first, "사용자 승인") })
            val labels = corpus.addLabels(positional[1], "wrong", user.filter { it.third.second }.map { com.dochiri.ilsim.runner.corpus.Label(it.first, "사용자 거절") })
            println("가져옴: 승인 ${user.count { it.third.first }} · 거절 ${user.count { it.third.second }} (라벨 맞다 ${labels.correct.size} · 틀리다 ${labels.wrong.size})")
        }
        "check" -> {
            val (clean, lines) = corpus.check(positional.drop(1))
            lines.forEach(::println)
            if (!clean) exitProcess(1)
        }
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

fun analyzeArchitecture(store: ConfigStore, id: String, out: Appendable) {
    val registered = store.load().projects[id]
    val path = Path.of(registered?.path ?: id).toAbsolutePath().normalize()
    val profile = StackDetector.detect(path)
    val archStore = ArchitectureStore(store)
    val mined = ArchitectureMiner.mine(path, profile)
    val recommended = ArchitectureRecommender.recommend(path, profile, mined)
    var model = RuleApproval.carryOver(registered?.let { archStore.load(id) }, mined.copy(rules = mined.rules + recommended))
    val self = if (mined.rules.isEmpty()) null else ArchitectureChecker.check(path, profile, model.copy(rules = model.rules.filterNot { Recommendations.isRecommended(it.id) }))
    if (registered != null) {
        out.appendLine("저장: ${archStore.save(id, model)} (프로젝트 ${id} 전용)")
    } else out.appendLine("등록되지 않은 경로라 저장하지 않았습니다 (먼저 add 로 등록해야 규칙이 프로젝트 전용으로 저장됩니다)")
    out.append(ArchitectureReport.summary(model.copy(rules = model.rules.filterNot { Recommendations.isRecommended(it.id) }), self))
    out.append(RecommendationReport.architecture(model.rules))
    if (registered != null && mined.rules.isNotEmpty()) {
        val cost = CostTally("ARCHITECTURE_REVIEW")
        try {
            val docs = DocumentContext.build(path, DocPurpose.ARCHITECTURE)
            if (!docs.isEmpty) out.appendLine("\n참고 문서(${docs.purpose.label}): ${docs.refs.joinToString { it.path }}")
            val (proposed, reviewable) = model.rules.partition { Recommendations.isRecommended(it.id) }
            model = ArchitectureReviewer.review(model.copy(rules = reviewable), AI_MODEL, docs = docs) { prompt ->
                val answer = com.dochiri.ilsim.runner.jev.JevRuleReview.review(prompt, reviewable.map { it.id to "${it.description} (관측 ${it.evidence.compliant}/${it.evidence.total})" }) { cost.ask(it, AI_MODEL) }
                if (answer.reviewer == "jev") cost.lastReviewer = "jev"
                answer.text
            }
            model = model.copy(review = model.review?.copy(reviewer = cost.lastReviewer ?: model.review!!.reviewer))
            model = RuleApproval.autoApprove(model).let { it.copy(rules = it.rules + proposed) }
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

private fun decide(store: ConfigStore, args: Array<String>, approve: Boolean) {
    val id = requireProject(store, args)
    val targets = args.drop(2)
    if (targets.isEmpty() || (!approve && targets == listOf("--all"))) { println(USAGE); exitProcess(1) }
    try {
        println(decideRules(store, id, targets, approve))
    } catch (e: IllegalArgumentException) {
        println(e.message); exitProcess(1)
    }
}

fun decideRules(store: ConfigStore, id: String, targets: List<String>, approve: Boolean): String {
    val archStore = ArchitectureStore(store)
    val convStore = ConventionStore(store)
    val arch = archStore.load(id)
    val conv = convStore.load(id)
    val all = approve && targets == listOf("--all")
    val archIds = if (all) arch?.let { RuleApproval.undecidedIds(it) }.orEmpty() else targets.filter { t -> arch?.rules?.any { it.id == t } == true }
    val convIds = if (all) conv?.let { ConventionApproval.undecidedIds(it) }.orEmpty() else targets.filter { t -> conv?.rules?.any { it.id == t } == true }
    val memory = ProjectMemory(store)
    val memoryIds = if (all) memory.pendingIds(id) else targets.filter { t -> memory.load(id).any { it.id == t } }
    val unknown = if (all) emptyList() else targets.filter { it !in archIds && it !in convIds && it !in memoryIds }
    require(unknown.isEmpty()) { "알 수 없는 규칙 id: ${unknown.joinToString()}" }
    if (arch != null && archIds.isNotEmpty()) archStore.save(id, if (approve) RuleApproval.approve(arch, archIds) else RuleApproval.reject(arch, archIds))
    if (conv != null && convIds.isNotEmpty()) convStore.save(id, if (approve) ConventionApproval.approve(conv, convIds) else ConventionApproval.reject(conv, convIds))
    if (memoryIds.isNotEmpty()) if (approve) memory.approve(id, memoryIds) else memory.reject(id, memoryIds)
    return "${if (approve) "승인" else "거절"}: 아키텍처 ${archIds.size}개 · 컨벤션 ${convIds.size}개 · 학습 메모리 ${memoryIds.size}개"
}

fun rulesReport(store: ConfigStore, id: String): String = buildString {
    val arch = ArchitectureStore(store).load(id)
    val conv = ConventionStore(store).load(id)
    arch?.let { appendLine("[아키텍처]"); append(ArchitectureReport.rules(it)) }
    conv?.let { appendLine("[코드 컨벤션]"); append(ConventionReport.rules(it)) }
    val pending = ProjectMemory(store).load(id).filter { it.status == "CANDIDATE" }
    if (pending.isNotEmpty()) {
        appendLine("[학습 메모리 후보]")
        pending.forEach { appendLine("  ${it.id} ${it.summary}") }
    }
}.ifBlank { "저장된 규칙이 없습니다. analyze_project(또는 architecture·convention)로 먼저 분석하세요" }

private fun docs(store: ConfigStore, args: Array<String>) {
    if (args.size < 2) { println(USAGE); exitProcess(1) }
    if (args[1] in setOf("add", "exclude", "remove")) {
        if (args.size < 4) { println(USAGE); exitProcess(1) }
        println(editDocs(store, args[2], args[1], args[3]))
        return
    }
    store.load().projects[args[1]]?.let { project ->
        val others = store.load().projects.filterKeys { it != args[1] }.values.map { Path.of(it.path) }
        val files = ProjectDocs.files(Path.of(project.path), project, others, null)
        println("[지침 문서] ${files.size}개 (추가 ${project.docs.size}, 제외 ${project.docsExclude.size})")
        files.forEach { (label, path) -> println("  - $label (구간 ${ProjectDocs.split(label, runCatching { path.readText() }.getOrDefault("")).size})") }
    }
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
    val cost = CostTally("RISK_REVIEW")
    try {
        val known = opinionStore.load(args[1])
        val updated = RiskReviewer.review(path, targets, known, "$AI_MODEL/${ClaudeModels.EFFORT}/${ReviewRouter.policyKey}", false,
            JevRiskScreen.batched(Jev.fromEnvironment(), path, targets), checkpoint = { opinionStore.save(args[1], it) }) { cost.ask(it, AI_MODEL) }
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

fun analyzeConvention(store: ConfigStore, id: String, out: Appendable) {
    val registered = store.load().projects[id]
    val path = Path.of(registered?.path ?: id).toAbsolutePath().normalize()
    val profile = StackDetector.detect(path)
    val previous = registered?.let { ConventionStore(store).load(id) }
    val mined = ConventionMiner.mine(path, profile)
    val recommended = ConventionRecommender.recommend(path, profile, mined)
    var model = ConventionApproval.carryOver(previous, mined.copy(rules = mined.rules + recommended))
    val self = if (mined.rules.isEmpty()) null else ConventionChecker.check(path, profile, model.copy(rules = model.rules.filterNot { Recommendations.isRecommended(it.id) }))
    if (registered != null) {
        out.appendLine("저장: ${ConventionStore(store).save(id, model)} (프로젝트 ${id} 전용)")
    } else out.appendLine("등록되지 않은 경로라 저장하지 않았습니다 (먼저 add 로 등록해야 규칙이 프로젝트 전용으로 저장됩니다)")
    out.append(ConventionReport.summary(model.copy(rules = model.rules.filterNot { Recommendations.isRecommended(it.id) }), self))
    out.append(RecommendationReport.convention(model.rules))
    if (registered != null && mined.rules.isNotEmpty()) {
        val cost = CostTally("CONVENTION_REVIEW")
        try {
            val docs = DocumentContext.build(path, DocPurpose.CONVENTION)
            if (!docs.isEmpty) out.appendLine("\n참고 문서(${docs.purpose.label}): ${docs.refs.joinToString { it.path }}")
            val (proposed, reviewable) = model.rules.partition { Recommendations.isRecommended(it.id) }
            model = ConventionReviewer.review(model.copy(rules = reviewable), AI_MODEL, docs = docs) { prompt ->
                val answer = com.dochiri.ilsim.runner.jev.JevRuleReview.review(prompt, reviewable.map { it.id to "${it.description} (관측 ${it.evidence.compliant}/${it.evidence.total})" }) { cost.ask(it, AI_MODEL) }
                if (answer.reviewer == "jev") cost.lastReviewer = "jev"
                answer.text
            }
            model = model.copy(review = model.review?.copy(reviewer = cost.lastReviewer ?: model.review!!.reviewer))
            model = ConventionApproval.autoApprove(model).let { it.copy(rules = it.rules + proposed) }
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

private fun guidelines(store: ConfigStore, args: Array<String>) {
    if (args.size < 3) { println(USAGE); exitProcess(1) }
    val config = store.load()
    val project = config.projects[args[1]] ?: run { println("등록되지 않은 프로젝트: ${args[1]}"); exitProcess(1) }
    RunnerToken.resolve(System.getenv("ILSIM_TOKEN"), store.location.parent.resolve("token"))
        ?.let { token -> runCatching { ServerClient(config.serverUrl, token).jevKey() }.onSuccess { JevKey.fromServer = it } }
    val jev = Jev.fromEnvironment()
    if (!jev.enabled) { println("JEV 키가 없습니다. ilsim-runner jev-key set 으로 등록하세요"); exitProcess(2) }
    val root = Path.of(project.path)
    val files = ProjectDocs.files(root, project, config.projects.filterKeys { it != args[1] }.values.map { Path.of(it.path) }, null)
    val sections = ProjectDocs.sections(files)
    val started = System.currentTimeMillis()
    val selection = Guidelines.selection(jev, args[2], sections) ?: run { println("JEV 호출 실패"); exitProcess(2) }
    val scored = selection.guidelines
    val selected = Guidelines.relevant(scored)
    println("문서 구간 ${sections.size}개 중 ${selected.size}개 선택(${System.currentTimeMillis() - started}ms)")
    Guidelines.documentScores(scored, selection.filtered).forEach { println(Guidelines.documentLine(it)) }
    scored.sortedByDescending { it.relevance }.forEach { println("  ${if (it in selected) "+" else "-"} ${"%.2f".format(it.relevance)} ${it.source} ${it.heading}") }
    val rendered = TaskGuide(args[1], true, sections.size, selected).render()
    val embedded = rendered.lines().count { it.startsWith("### ") }
    println("지시에 넣는 분량: ${rendered.length}자(원문 ${embedded}개, 출처만 ${selected.size - embedded}개)")
    val base = args.getOrNull(3) ?: return
    val diff = Shell.gitOk(root, "diff", base)
    val check = Guidelines.check(jev, "IMPLEMENTATION", selected, diff) ?: run { println("JEV 호출 실패"); exitProcess(2) }
    println("위반 판정(diff ${diff.length}자${if (check.truncated) ", 일부만" else ""}): 위반 ${check.violated.size}, 애매 ${check.uncertain.size}")
    check.probabilities.entries.sortedByDescending { it.value }.forEach { (g, p) ->
        println("  ${when { g in check.violated -> "위반"; g in check.uncertain -> "애매"; else -> "통과" }} ${"%.2f".format(p)} ${g.source} ${g.heading}")
    }
}

private fun mcp(store: ConfigStore) {
    val protocol = System.out
    System.setOut(System.err)
    val token = RunnerToken.resolve(System.getenv("ILSIM_TOKEN"), store.location.parent.resolve("token"))
        ?: run { System.err.println("토큰이 없습니다. 서버를 먼저 실행해 ~/.ilsim/token 을 만들거나 ILSIM_TOKEN 환경변수를 설정하세요"); exitProcess(1) }
    val client = ServerClient(store.load().serverUrl, token)
    JevKey.loader = { client.jevKey() }
    JevKey.refresh()
    val cwd = Path.of(System.getenv("CLAUDE_PROJECT_DIR")?.takeIf { it.isNotBlank() } ?: System.getProperty("user.dir"))
    val tools = IlsimMcp(store, client, ActiveTasks(store.location.parent.resolve("active")), cwd).tools()
    McpServer("ilsim", "0.1.0", IlsimMcp.INSTRUCTIONS, tools).serve(System.`in`.bufferedReader(), protocol)
}

private fun jevKey(store: ConfigStore, args: Array<String>) {
    val token = RunnerToken.resolve(System.getenv("ILSIM_TOKEN"), store.location.parent.resolve("token"))
        ?: run { println("토큰이 없습니다. 서버를 먼저 실행해 ~/.ilsim/token 을 만들거나 ILSIM_TOKEN 환경변수를 설정하세요"); exitProcess(1) }
    val client = ServerClient(store.load().serverUrl, token)
    try {
        when (args.getOrNull(1)) {
            "set" -> {
                val key = (System.console()?.readPassword("JEV API 키: ")?.let(::String) ?: readLine()).orEmpty().trim()
                if (key.isEmpty()) { println("키가 비어 있습니다"); exitProcess(1) }
                client.saveJevKey(key)
                println("JEV 키를 저장했습니다(서버에 암호화 저장). 일심 MCP를 다시 연결하면 반영됩니다")
            }
            "delete" -> { client.deleteJevKey(); println("JEV 키를 지웠습니다") }
            "status" -> println(client.jevKeyStatus())
            else -> { println(USAGE); exitProcess(1) }
        }
    } catch (e: IllegalStateException) {
        println(e.message); exitProcess(1)
    }
}

private fun hook(store: ConfigStore, args: Array<String>) {
    val event = args.getOrNull(1) ?: exitProcess(0)
    if (System.getenv(Shell.RUNNER_ENV) == "1") exitProcess(0)
    val output = try {
        if (event == "commit") Hooks.commit(Path.of(System.getProperty("user.dir")), store, System.getenv())
        else Hooks.handle(event, System.`in`.bufferedReader().readText(), store, ActiveTasks(store.location.parent.resolve("active")))
    } catch (e: Exception) {
        System.err.println("일심 훅 오류: ${e.message}")
        Hooks.Output()
    }
    if (output.stdout.isNotEmpty()) (if (event == "commit") System.err else System.out).println(output.stdout)
    exitProcess(output.exit)
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

private fun jevEval(args: Array<String>) {
    val rest = args.drop(1)
    fun option(name: String) = rest.zipWithNext().firstOrNull { it.first == name }?.second
    val jev = Jev.uncached()
    if (!jev.enabled) { println("JEV 키가 없습니다. 환경 변수 TYPESAFE_API_KEY 를 설정하세요"); exitProcess(1) }
    val repeat = option("--repeat")?.toIntOrNull()?.coerceIn(1, 5) ?: 1
    val cost = CostTally(if (rest.firstOrNull() == "requirements") "REVIEW" else "RISK_REVIEW")
    fun save(text: String) = option("--out")?.let { out -> Path.of(out).also { it.toAbsolutePath().parent.createDirectories() }.writeText(text); println("저장: $out") }
    if (rest.firstOrNull() == "requirements") {
        val dir = Path.of(option("--data") ?: "ilsim-runner/corpus/jev-requirement").toAbsolutePath().normalize()
        val samples = JevRequirementEval.load(dir)
        if (samples.isEmpty()) { println("평가 사례가 없습니다: $dir"); exitProcess(1) }
        val runs = JevRequirementEval.jev(samples, jev, repeat).toMutableList()
        if ("--agent" in rest) runs += JevRequirementEval.agent(samples, "Agent($AI_MODEL)") { cost.ask(it, AI_MODEL) }
        println(JevRequirementEval.report(samples, runs))
        cost.summary()?.let(::println)
        save(JevRequirementEval.toJson(samples, runs))
        return
    }
    val projects = rest.zipWithNext().filter { it.first == "--project" }.map { Path.of(it.second).toAbsolutePath().normalize() }
    val dir = Path.of(option("--data") ?: "ilsim-runner/corpus/jev-risk").toAbsolutePath().normalize()
    val samples = if (projects.isEmpty()) JevRiskEval.load(dir) else JevRiskEval.unlabeled(projects, option("--limit")?.toIntOrNull() ?: 20)
    if (samples.isEmpty()) { println("평가 사례가 없습니다: ${projects.ifEmpty { listOf(dir) }.joinToString()}"); exitProcess(1) }
    val runs = mutableListOf(JevRiskEval.batched(samples, jev, repeat), JevRiskEval.single(samples, jev, repeat))
    if ("--agent" in rest) runs += JevRiskEval.agent(samples, "Agent($AI_MODEL)") { cost.ask(it, AI_MODEL) }
    println(if (projects.isEmpty()) JevRiskEval.report(samples, runs) else JevRiskEval.disagreements(samples, runs))
    cost.summary()?.let(::println)
    save(JevRiskEval.toJson(samples, runs))
}

private fun recommend(store: ConfigStore, args: Array<String>) {
    require(args.size >= 2) { "사용법: ilsim-runner recommend <id|경로>" }
    val path = Path.of(store.load().projects[args[1]]?.path ?: args[1]).toAbsolutePath().normalize()
    val profile = StackDetector.detect(path)
    val architecture = ArchitectureMiner.mine(path, profile)
    val convention = ConventionMiner.mine(path, profile)
    print(RecommendationReport.architecture(ArchitectureRecommender.recommend(path, profile, architecture)))
    print(RecommendationReport.convention(ConventionRecommender.recommend(path, profile, convention)))
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

fun editDocs(store: ConfigStore, id: String, action: String, target: String): String {
    val config = store.load()
    val project = config.projects[id] ?: throw IllegalArgumentException("등록되지 않은 프로젝트: $id")
    val root = Path.of(project.path).toAbsolutePath().normalize()
    val absolute = Path.of(target).let { if (it.isAbsolute) it else Path.of(System.getProperty("user.dir")).resolve(it) }.normalize()
    val value = if (absolute.startsWith(root)) root.relativize(absolute).toString().replace('\\', '/') else absolute.toString()
    val updated = when (action) {
        "add" -> {
            require(absolute.exists()) { "경로가 없습니다: $absolute" }
            project.copy(docs = (project.docs + value).distinct(), docsExclude = project.docsExclude - value)
        }
        "exclude" -> project.copy(docsExclude = (project.docsExclude + value).distinct(), docs = project.docs - value)
        "remove" -> project.copy(docs = project.docs - value, docsExclude = project.docsExclude - value)
        else -> throw IllegalArgumentException("알 수 없는 동작: $action")
    }
    store.save(config.copy(projects = config.projects + (id to updated)))
    return "지침 문서 $action: $id $value"
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

