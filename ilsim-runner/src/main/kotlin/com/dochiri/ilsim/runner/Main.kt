package com.dochiri.ilsim.runner

import com.dochiri.ilsim.runner.architecture.ArchitectureChecker
import com.dochiri.ilsim.runner.architecture.CrossValidation
import com.dochiri.ilsim.runner.architecture.SourceScanner
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
import com.dochiri.ilsim.runner.convention.DocRuleStore
import com.dochiri.ilsim.runner.convention.DocRules
import com.dochiri.ilsim.runner.convention.FactScanner
import com.dochiri.ilsim.runner.document.DocPurpose
import com.dochiri.ilsim.runner.document.DocumentContext
import com.dochiri.ilsim.runner.document.ProjectDocs
import com.dochiri.ilsim.runner.jev.Guidelines
import com.dochiri.ilsim.runner.jev.Jev
import com.dochiri.ilsim.runner.jev.JevGuidelineEval
import com.dochiri.ilsim.runner.jev.JevRequirementEval
import com.dochiri.ilsim.runner.jev.JevScopeEval
import com.dochiri.ilsim.runner.jev.JevScopeChangeEval
import com.dochiri.ilsim.runner.jev.JevTestCodeEval
import com.dochiri.ilsim.runner.jev.JevUndecidedEval
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
  규칙 만들기 (정적 분석 → AI 1회 검토 → 판정대로 자동 승인·거절, 사용자는 바꿀 때만 approve·reject)
    ilsim-runner architecture <id>               아키텍처 규칙
    ilsim-runner convention <id>                 코드 컨벤션 규칙
    ilsim-runner doc-rules <id>                  그 프로젝트 문서에 적힌 규칙으로 문서 지침 검증기(doc: 규칙)를 만든다(JEV·Claude)
    ilsim-runner metrics <id>                    제출 결과·실패 원인·Claude 호출·비용·병합 여부 지표(서버 기록 기준)
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
    ilsim-runner remote <https://주소>|local|status  원격 일심 서버에 붙기(Runner 토큰·Cloudflare Access 서비스 토큰을 표준 입력으로 받아 ~/.ilsim 에 저장), local 은 이 PC 서버로 되돌림
    ilsim-runner jev-eval [--data <폴더>] [--repeat <n>] [--agent] [--out <파일>]   주입형 사례로 JEV 위험 선별 평가 (코드 발췌가 TypeSafe로 전송, --agent는 Claude 비교)
    ilsim-runner jev-eval --project <경로>... [--limit <n>] [--agent]   실제 코드 후보에서 판단이 갈린 것만 추림 (라벨 없음)
    ilsim-runner jev-eval requirements [--data <폴더>] [--repeat <n>] [--agent] [--out <파일>]   주입형 diff로 요구사항 충족 판정 평가
    ilsim-runner jev-eval scope [--data <폴더>] [--repeat <n>] [--out <파일>]   요구사항 문서의 영향 범위 밖 변경 판정 평가
    ilsim-runner jev-eval scope-change [--data <폴더>] [--repeat <n>] [--out <파일>]   요구사항과 관계없는 변경 묶음(같은 파일 안 포함) 판정 평가
    ilsim-runner jev-eval undecided [--data <폴더>] [--repeat <n>] [--out <파일>]   요구사항 문서의 미결정 사항을 추측해 정했는지 판정 평가
    ilsim-runner jev-eval testcode [--data <폴더>] [--repeat <n>] [--out <파일>]   테스트 코드의 TC 누락·검증 없이 통과하는 테스트 판정 평가
    ilsim-runner jev-eval guidelines [--data <폴더>] [--repeat <n>] [--out <파일>] [--sections] [--rules]   정답 있는 문서·요청·변경으로 지침 선택·위반·문서 규칙 판정 평가(--rules는 문서 규칙 질문만)
                                                 jev-eval 공통: 1회차 판정은 ~/.ilsim/eval-decisions에 저장해 같은 질문을 다시 보내지 않음. --repeat의 2회차부터와 --fresh는 저장된 판정 없이 새로 물음
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
        "doc-rules" -> docRules(store, args)
        "docs" -> docs(store, args)
        "metrics" -> metrics(store, args)
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
        "remote" -> remote(store, args)
        "hook" -> hook(store, args)
        "jev-eval" -> jevEval(store, args)
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
    val crossValidated = if (mined.rules.isEmpty()) CrossValidation.Result(emptyMap()) else CrossValidation.check(SourceScanner.scan(path, profile.modules), mined)
    val violating = self.orEmpty().map { it.ruleId }.toSet() + crossValidated.unstable.keys
    model = RuleApproval.autoApprove(model, violating)
    if (registered != null) {
        out.appendLine("저장: ${archStore.save(id, model)} (프로젝트 ${id} 전용)")
    } else out.appendLine("등록되지 않은 경로라 저장하지 않았습니다 (먼저 add 로 등록해야 규칙이 프로젝트 전용으로 저장됩니다)")
    out.append(ArchitectureReport.summary(model.copy(rules = model.rules.filterNot { Recommendations.isRecommended(it.id) }), self))
    out.append(RecommendationReport.architecture(model.rules))
    if (crossValidated.unstable.isNotEmpty()) {
        out.appendLine("교차 검증(단위를 하나씩 빼고 다시 뽑아 뺀 단위에 적용): 새 단위에서 어긋나는 규칙 ${crossValidated.unstable.size}개는 막지 않고 검토로만 씁니다")
        crossValidated.unstable.entries.take(20).forEach { (id, units) -> out.appendLine("  ~ $id (${units.joinToString()})") }
    }
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
            model = RuleApproval.autoApprove(model, violating).let { it.copy(rules = it.rules + proposed) }
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
            println(ArchitectureReport.violations(v).trimEnd().prependIndent("  "))
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
        println(ArchitectureReport.violations(v).trimEnd().prependIndent("  "))
        if (v.isNotEmpty()) failed = true
    }

    println("[문서 지침 검증기]")
    val docs = DocRuleStore(store).load(id)
    if (docs == null) println("  규칙 없음 (doc-rules $id 를 먼저 실행)")
    else {
        val v = ConventionChecker.check(path, profile, DocRules.asConvention(docs))
        println(ArchitectureReport.violations(v).trimEnd().prependIndent("  "))
        if (v.isNotEmpty()) failed = true
    }
    if (failed) exitProcess(1)
}

private fun rulesCmd(store: ConfigStore, args: Array<String>) {
    val id = requireProject(store, args)
    val arch = ArchitectureStore(store).load(id)
    val conv = ConventionStore(store).load(id)
    val docs = DocRuleStore(store).load(id)
    if (arch == null && conv == null && docs == null) { println("저장된 규칙이 없습니다. architecture $id, convention $id, doc-rules $id 를 먼저 실행하세요"); exitProcess(2) }
    arch?.let { println("[아키텍처]"); print(ArchitectureReport.rules(it)) }
    conv?.let { println("[코드 컨벤션]"); print(ConventionReport.rules(it)) }
    docs?.let { println("[문서 지침 검증기]"); print(DocRules.report(it).trimStart('\n')) }
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
    val docStore = DocRuleStore(store)
    val docModel = docStore.load(id)
    val docIds = if (all) docModel?.let { DocRules.undecidedIds(it) }.orEmpty() else targets.filter { t -> docModel?.rules?.any { it.id == t } == true }
    val memory = ProjectMemory(store)
    val memoryIds = if (all) memory.pendingIds(id) else targets.filter { t -> memory.load(id).any { it.id == t } }
    val unknown = if (all) emptyList() else targets.filter { it !in archIds && it !in convIds && it !in docIds && it !in memoryIds }
    require(unknown.isEmpty()) { "알 수 없는 규칙 id: ${unknown.joinToString()}" }
    if (arch != null && archIds.isNotEmpty()) archStore.save(id, if (approve) RuleApproval.approve(arch, archIds) else RuleApproval.reject(arch, archIds))
    if (conv != null && convIds.isNotEmpty()) convStore.save(id, if (approve) ConventionApproval.approve(conv, convIds) else ConventionApproval.reject(conv, convIds))
    if (docIds.isNotEmpty()) docStore.update(id) { DocRules.decide(checkNotNull(it), docIds, approve) }
    if (memoryIds.isNotEmpty()) if (approve) memory.approve(id, memoryIds) else memory.reject(id, memoryIds)
    return "${if (approve) "승인" else "거절"}: 아키텍처 ${archIds.size}개 · 컨벤션 ${convIds.size}개 · 문서 규칙 ${docIds.size}개 · 학습 메모리 ${memoryIds.size}개"
}

fun rulesReport(store: ConfigStore, id: String): String = buildString {
    val arch = ArchitectureStore(store).load(id)
    val conv = ConventionStore(store).load(id)
    arch?.let { appendLine("[아키텍처]"); append(ArchitectureReport.rules(it)) }
    conv?.let { appendLine("[코드 컨벤션]"); append(ConventionReport.rules(it)) }
    DocRuleStore(store).load(id)?.let { appendLine("[문서 지침 검증기]"); append(DocRules.report(it).trimStart('\n')) }
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
        println("[지침 문서] ${files.size}개 (추가 ${project.docs.size}, 제외 ${project.docsExclude.size}, 저장소 ${ProjectDocs.IGNORE_FILE} ${ProjectDocs.ignored(Path.of(project.path)).size})")
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

private fun docRules(store: ConfigStore, args: Array<String>) {
    if (args.size < 2) { println(USAGE); exitProcess(1) }
    loadJevKey(store, store.load())
    analyzeDocRules(store, args[1], System.out)
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
    val violating = self.orEmpty().map { it.ruleId }.toSet()
    model = ConventionApproval.autoApprove(model, violating)
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
            model = ConventionApproval.autoApprove(model, violating).let { it.copy(rules = it.rules + proposed) }
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

fun analyzeDocRules(store: ConfigStore, id: String, out: Appendable) {
    val config = store.load()
    val project = config.projects[id] ?: run { out.appendLine("등록되지 않은 프로젝트라 문서 지침 검증기를 만들지 않았습니다: $id (먼저 add 로 등록)"); return }
    val path = Path.of(project.path).toAbsolutePath().normalize()
    val profile = StackDetector.detect(path)
    val facts = FactScanner.scan(path, profile.modules)
    val docs = projectDocs(config, id, path, project)
    val rules = DocRuleStore(store)
    if (!DocRules.applicable(profile.buildTool, facts)) {
        rules.save(id, DocRules.DocRuleModel(notes = listOf("Java·Kotlin 소스와 빌드 파일이 없어 정적 검증기로 옮기지 않음"), files = docs.keys.sorted(), applicable = false))
        out.appendLine("Java·Kotlin 소스와 빌드 파일이 없어 문서 지침 검증기를 만들지 않았습니다: $id (문서 지침은 제출 때 JEV 판정으로 확인)")
        return
    }
    val sections = ProjectDocs.sections(docs)
    val cost = CostTally("DOC_RULES")
    val extracted = DocRules.extract(sections, facts, rules.load(id), Jev.fromEnvironment()) { cost.ask(it, AI_MODEL) }
    var model: DocRules.DocRuleModel? = null
    val file = rules.update(id) { latest -> DocRules.carryOver(latest, extracted).copy(files = docs.keys.sorted()).also { model = it } }
    out.appendLine("저장: $file (프로젝트 $id 문서 지침 검증기, 문서 구간 ${sections.size}개)")
    out.append(DocRules.report(checkNotNull(model)))
    extracted.notes.forEach { out.appendLine("  ! $it") }
    cost.summary()?.let { out.appendLine("문서 규칙 $it") }
}

fun projectDocs(config: RunnerConfig, id: String, root: Path, project: ProjectEntry): Map<String, Path> =
    ProjectDocs.files(root, project, config.projects.filterKeys { it != id }.values.map { Path.of(it.path) }, null).filterKeys { !Gate.isArtifact(it) }

fun pendingRules(store: ConfigStore, id: String): Map<String, Int> = listOfNotNull(
    ArchitectureStore(store).load(id)?.let { "아키텍처" to RuleApproval.undecidedIds(it).size },
    ConventionStore(store).load(id)?.let { "컨벤션" to ConventionApproval.undecidedIds(it).filterNot(DocRules::isDocRule).size },
    DocRuleStore(store).load(id)?.let { "문서 규칙" to DocRules.undecidedIds(it).size },
).filter { it.second > 0 }.toMap()

fun analysisGaps(store: ConfigStore, id: String, docs: Map<String, Path>): List<String> {
    val gaps = mutableListOf<String>()
    if (ArchitectureStore(store).load(id) == null) gaps += "아키텍처 분석이 없습니다"
    if (ConventionStore(store).load(id) == null) gaps += "컨벤션 분석이 없습니다"
    val model = DocRuleStore(store).load(id)
    when {
        model == null -> gaps += "문서 지침 검증기가 없습니다"
        !model.applicable -> {}
        model.files.isEmpty() && model.documents.isNotEmpty() -> gaps += "문서 지침 검증기가 예전 형식이라 문서가 바뀌었는지 알 수 없습니다"
        else -> {
            val added = (docs.keys - model.files.toSet()).sorted()
            if (added.isNotEmpty()) gaps += "분석 뒤 생긴 문서 ${added.take(5).joinToString()}"
            val sections = ProjectDocs.sections(docs)
            val changed = model.documents.filter { d -> sections.none { it.path == d.path && DocRules.sectionHash(it) == d.hash } }
            if (changed.isNotEmpty()) gaps += "분석 뒤 바뀌거나 사라졌거나 검증기 버전이 달라진 문서 규칙 구간 ${changed.take(5).joinToString { "${it.path}:${it.line} ${it.heading}" }}"
        }
    }
    return gaps
}

private fun metrics(store: ConfigStore, args: Array<String>) {
    if (args.size < 2) { println(USAGE); exitProcess(1) }
    val config = store.load()
    val project = config.projects[args[1]] ?: run { println("등록되지 않은 프로젝트: ${args[1]}"); exitProcess(1) }
    val token = RunnerToken.resolve(System.getenv("ILSIM_TOKEN"), store.location.parent.resolve("token")) ?: run { println("Runner 토큰이 없습니다"); exitProcess(1) }
    val metrics = serverClient(store, token).metrics(args[1])
    println(MetricsReport.render(args[1], metrics, MetricsReport.merged(Path.of(project.path), metrics.summaries)))
}

private fun loadJevKey(store: ConfigStore, config: RunnerConfig) {
    RunnerToken.resolve(System.getenv("ILSIM_TOKEN"), store.location.parent.resolve("token"))
        ?.let { token -> runCatching { serverClient(store, token).jevKey() }.onSuccess { JevKey.fromServer = it } }
}

private fun guidelines(store: ConfigStore, args: Array<String>) {
    if (args.size < 3) { println(USAGE); exitProcess(1) }
    val config = store.load()
    val project = config.projects[args[1]] ?: run { println("등록되지 않은 프로젝트: ${args[1]}"); exitProcess(1) }
    loadJevKey(store, config)
    val jev = Jev.fromEnvironment()
    if (!jev.enabled) { println("JEV 키가 없습니다. ilsim-runner jev-key set 으로 등록하세요"); exitProcess(2) }
    val root = Path.of(project.path)
    val files = ProjectDocs.files(root, project, config.projects.filterKeys { it != args[1] }.values.map { Path.of(it.path) }, null)
    val sections = ProjectDocs.sections(files)
    val started = System.currentTimeMillis()
    val selection = Guidelines.selection(jev, args[2], sections, ProjectDocs.modules(root)) ?: run { println("JEV 호출 실패"); exitProcess(2) }
    val scored = selection.guidelines
    val selected = Guidelines.relevant(scored)
    println("문서 구간 ${sections.size}개 중 ${selected.size}개 선택(${System.currentTimeMillis() - started}ms)")
    Guidelines.documentScores(scored, selection.filtered).forEach { println(Guidelines.documentLine(it)) }
    scored.sortedByDescending { it.relevance }.forEach { println("  ${if (it in selected) "+" else "-"} ${"%.2f".format(it.relevance)} ${it.source} ${it.heading}") }
    val rendered = TaskGuide(args[1], true, sections.size, selected).render()
    val sources = selected.map { "### ${it.source} " }.toSet()
    val embedded = rendered.lines().count { line -> sources.any { line.startsWith(it) } }
    println("지시에 넣는 분량: ${rendered.length}자(원문 ${embedded}개, 출처만 ${selected.size - embedded}개)")
    val base = args.getOrNull(3) ?: return
    val diff = Shell.gitOk(root, "diff", base)
    val check = Guidelines.check(jev, "IMPLEMENTATION", selected, diff) ?: run { println("JEV 호출 실패"); exitProcess(2) }
    println("위반 판정(diff ${diff.length}자${if (check.truncated) ", 일부만" else ""}): 위반 ${check.violated.size}, 애매 ${check.uncertain.size}")
    check.probabilities.entries.sortedByDescending { it.value }.forEach { (g, p) ->
        val parts = check.parts.filter { it.guideline == g && it.before != null }
        println("  ${when { g in check.violated -> "위반"; g in check.uncertain -> "애매"; else -> "통과" }} ${"%.2f".format(p)} ${g.source} ${g.heading}" +
            parts.joinToString("") { " [원래 ${it.raw?.let { v -> "%.2f".format(v) }} 기존 코드 ${it.before?.let { v -> "%.2f".format(v) }} 추가 줄 ${it.added?.let { v -> "%.2f".format(v) }}]" })
    }
}

private fun mcp(store: ConfigStore) {
    val protocol = System.out
    System.setOut(System.err)
    val token = RunnerToken.resolve(System.getenv("ILSIM_TOKEN"), store.location.parent.resolve("token"))
        ?: run { System.err.println("토큰이 없습니다. 서버를 먼저 실행해 ~/.ilsim/token 을 만들거나 ILSIM_TOKEN 환경변수를 설정하세요"); exitProcess(1) }
    val client = serverClient(store, token)
    JevKey.loader = { client.jevKey() }
    JevKey.refresh()
    val cwd = Path.of(System.getenv("CLAUDE_PROJECT_DIR")?.takeIf { it.isNotBlank() } ?: System.getProperty("user.dir"))
    val tools = IlsimMcp(store, client, ActiveTasks(store.location.parent.resolve("active")), cwd).tools()
    McpServer("ilsim", "0.1.0", IlsimMcp.INSTRUCTIONS, tools).serve(System.`in`.bufferedReader(), protocol)
}

fun serverClient(store: ConfigStore, token: String): ServerClient =
    ServerClient(store.load().serverUrl, token, RunnerAccess.headers(System.getenv(), store.location.parent.resolve("access.json")))

private fun remote(store: ConfigStore, args: Array<String>) {
    val home = store.location.parent
    fun secret(prompt: String): String = (System.console()?.readPassword(prompt)?.let(::String) ?: readLine()).orEmpty().trim()
    when (val target = args.getOrNull(1)) {
        null, "status" -> {
            val config = store.load()
            println("서버: ${config.serverUrl}")
            println("Runner 토큰: ${if (RunnerToken.resolve(System.getenv("ILSIM_TOKEN"), home.resolve("token")) != null) "있음" else "없음"}")
            println("Cloudflare Access 서비스 토큰: ${if (RunnerAccess.headers(System.getenv(), home.resolve("access.json")).isNotEmpty()) "있음" else "없음"}")
        }
        "local" -> {
            store.save(store.load().copy(serverUrl = "http://localhost:8080"))
            home.resolve("access.json").toFile().delete()
            println("서버를 이 PC(http://localhost:8080)로 되돌리고 Access 서비스 토큰을 지웠습니다")
        }
        else -> {
            if (!target.startsWith("https://") && !target.startsWith("http://localhost") && !target.startsWith("http://127.0.0.1")) {
                println("원격 서버 주소는 https:// 로 시작해야 합니다"); exitProcess(1)
            }
            val token = secret("Runner 토큰(서버 PC의 ~/.ilsim/token 내용): ")
            if (token.isEmpty()) { println("토큰이 비어 있습니다"); exitProcess(1) }
            val id = secret("Cloudflare Access Client ID(없으면 비워 둠): ")
            val clientSecret = if (id.isEmpty()) "" else secret("Cloudflare Access Client Secret: ")
            Secrets.write(home.resolve("token"), token + "\n")
            if (id.isNotEmpty()) RunnerAccess.save(home.resolve("access.json"), AccessToken(id, clientSecret)) else home.resolve("access.json").toFile().delete()
            store.save(store.load().copy(serverUrl = target.trimEnd('/')))
            val status = runCatching { serverClient(store, token).jevKeyStatus() }
            println(status.fold({ "연결했습니다: $target (JEV 키 상태: $it)" }, { "설정은 저장했지만 연결 확인에 실패했습니다: ${it.message}" }))
        }
    }
}

private fun jevKey(store: ConfigStore, args: Array<String>) {
    val token = RunnerToken.resolve(System.getenv("ILSIM_TOKEN"), store.location.parent.resolve("token"))
        ?: run { println("토큰이 없습니다. 서버를 먼저 실행해 ~/.ilsim/token 을 만들거나 ILSIM_TOKEN 환경변수를 설정하세요"); exitProcess(1) }
    val client = serverClient(store, token)
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

private fun jevEval(store: ConfigStore, args: Array<String>) {
    val rest = args.drop(1)
    fun option(name: String) = rest.zipWithNext().firstOrNull { it.first == name }?.second
    if (rest.firstOrNull() == "guidelines") return guidelineEval(store, rest, option("--data"), option("--repeat"), option("--out"))
    val jev = if ("--fresh" in rest) Jev.uncached() else Jev.cached(evalDecisions(store))
    val fresh = Jev.uncached()
    if (!jev.enabled) { println("JEV 키가 없습니다. 환경 변수 TYPESAFE_API_KEY 를 설정하세요"); exitProcess(1) }
    val repeat = option("--repeat")?.toIntOrNull()?.coerceIn(1, 5) ?: 1
    val cost = CostTally(if (rest.firstOrNull() == "requirements") "REVIEW" else "RISK_REVIEW")
    fun save(text: String) = option("--out")?.let { out -> Path.of(out).also { it.toAbsolutePath().parent.createDirectories() }.writeText(text); println("저장: $out") }
    try {
        if (rest.firstOrNull() == "scope") {
            val dir = Path.of(option("--data") ?: "ilsim-runner/corpus/jev-scope").toAbsolutePath().normalize()
            val samples = JevScopeEval.load(dir)
            if (samples.isEmpty()) { println("평가 사례가 없습니다: $dir"); exitProcess(1) }
            val records = JevScopeEval.run(samples, jev, repeat, fresh)
            println(JevScopeEval.report(records))
            save(JevScopeEval.toJson(records))
            return
        }
        if (rest.firstOrNull() == "scope-change") {
            val dir = Path.of(option("--data") ?: "ilsim-runner/corpus/jev-scope-change").toAbsolutePath().normalize()
            val samples = JevScopeChangeEval.load(dir)
            if (samples.isEmpty()) { println("평가 사례가 없습니다: $dir"); exitProcess(1) }
            val records = JevScopeChangeEval.run(samples, jev, repeat, fresh)
            println(JevScopeChangeEval.report(records))
            save(JevScopeChangeEval.toJson(records))
            return
        }
        if (rest.firstOrNull() == "testcode") {
            val dir = Path.of(option("--data") ?: "ilsim-runner/corpus/jev-testcode").toAbsolutePath().normalize()
            val samples = JevTestCodeEval.load(dir)
            if (samples.isEmpty()) { println("평가 사례가 없습니다: $dir"); exitProcess(1) }
            val records = JevTestCodeEval.run(samples, jev, repeat, fresh)
            println(JevTestCodeEval.report(records))
            save(JevTestCodeEval.toJson(records))
            return
        }
        if (rest.firstOrNull() == "undecided") {
            val dir = Path.of(option("--data") ?: "ilsim-runner/corpus/jev-undecided").toAbsolutePath().normalize()
            val samples = JevUndecidedEval.load(dir)
            if (samples.isEmpty()) { println("평가 사례가 없습니다: $dir"); exitProcess(1) }
            val records = JevUndecidedEval.run(samples, jev, repeat, fresh)
            println(JevUndecidedEval.report(records))
            save(JevUndecidedEval.toJson(records))
            return
        }
        if (rest.firstOrNull() == "requirements") {
            val dir = Path.of(option("--data") ?: "ilsim-runner/corpus/jev-requirement").toAbsolutePath().normalize()
            val samples = JevRequirementEval.load(dir)
            if (samples.isEmpty()) { println("평가 사례가 없습니다: $dir"); exitProcess(1) }
            val runs = JevRequirementEval.jev(samples, jev, repeat, fresh).toMutableList()
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
        val runs = mutableListOf(JevRiskEval.batched(samples, jev, repeat, fresh), JevRiskEval.single(samples, jev, repeat, fresh))
        if ("--agent" in rest) runs += JevRiskEval.agent(samples, "Agent($AI_MODEL)") { cost.ask(it, AI_MODEL) }
        println(if (projects.isEmpty()) JevRiskEval.report(samples, runs) else JevRiskEval.disagreements(samples, runs))
        cost.summary()?.let(::println)
        save(JevRiskEval.toJson(samples, runs))
    } finally {
        println(jevCalls(listOf(jev, fresh)))
    }
}

private fun evalDecisions(store: ConfigStore): Path = store.location.toAbsolutePath().parent.resolve("eval-decisions")

private fun jevCalls(clients: List<Jev>): String =
    "JEV 새 호출 ${clients.sumOf { it.calls.get() }}회, 저장된 판정 재사용 ${clients.sumOf { it.reused.get() }}회"

private fun guidelineEval(store: ConfigStore, rest: List<String>, data: String?, repeat: String?, out: String?) {
    val dir = Path.of(data ?: "ilsim-runner/corpus/jev-guideline").toAbsolutePath().normalize()
    val projects = JevGuidelineEval.load(dir)
    if (projects.isEmpty()) { println("평가 사례가 없습니다: $dir"); exitProcess(1) }
    val problems = projects.flatMap(JevGuidelineEval::problems)
    if ("--sections" in rest) {
        projects.forEach { print(JevGuidelineEval.sections(it)) }
        problems.forEach(::println)
        return
    }
    if (problems.isNotEmpty()) { problems.forEach(::println); exitProcess(1) }
    loadJevKey(store, store.load())
    if (!Jev.uncached().enabled) { println("JEV 키가 없습니다. ilsim-runner jev-key set 으로 등록하세요"); exitProcess(2) }
    val rounds = repeat?.toIntOrNull()?.coerceIn(1, 5) ?: 1
    val caches = mutableListOf<Path>()
    val clients = mutableListOf<Jev>()
    val observations = JevGuidelineEval.run(projects, rounds, "--rules" in rest) { round ->
        (if (round == 1 && "--fresh" !in rest) Jev.cached(evalDecisions(store)) else Jev.cached(java.nio.file.Files.createTempDirectory("ilsim-jev-eval").also(caches::add))).also(clients::add)
    }
    println(JevGuidelineEval.report(projects, observations))
    println(jevCalls(clients))
    caches.forEach { it.toFile().deleteRecursively() }
    out?.let { Path.of(it).also { f -> f.toAbsolutePath().parent.createDirectories() }.writeText(JevGuidelineEval.toJson(observations)); println("저장: $it") }
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

