package com.dochiri.ilsim.runner.architecture

object ArchitectureReport {

    fun summary(m: ArchitectureModel, selfCheck: List<Violation>?): String = buildString {
        appendLine("아키텍처 추론: ${m.projectName} (소스 ${m.sourceFiles}개)")
        appendLine("루트 패키지: ${m.layout.rootPrefix.ifEmpty { "(없음)" }} · 모드: ${m.layout.mode}")
        if (m.layout.mode == "SLICED") {
            appendLine("슬라이스(반복되는 형제 구조): ${m.layout.slices.joinToString()}")
            if (m.layout.shared.isNotEmpty()) appendLine("공유 커널(모든 슬라이스가 쓰고 아무것도 쓰지 않는 슬라이스): ${m.layout.shared.joinToString()}")
            appendLine("슬라이스 내부 레이어: ${m.layout.layerNames.joinToString()}")
        }
        if (m.layerLevels.isNotEmpty()) {
            appendLine("의존 수준(위 수준이 아래 수준에 의존, 같은 수준끼리는 순서 없음):")
            m.layerLevels.forEachIndexed { i, names -> appendLine("  $i: ${names.joinToString(", ")}") }
        }
        appendLine("적용 가능성: ${m.applicability.status}")
        m.applicability.reasons.forEach { appendLine("  - $it") }
        appendLine()
        appendLine("단위별 파일 수 / 사용하는 외부 라이브러리:")
        m.layers.forEach { l ->
            val ext = if (l.externals.isEmpty()) "외부 없음" else if (l.externals.size > 5) "${l.externals.size}종" else l.externals.joinToString()
            appendLine("  - ${l.name} (${l.files}): $ext")
        }
        appendLine()
        appendLine("단위 간 의존:")
        m.layerEdges.forEach { appendLine("  ${it.from} → ${it.to} (${it.files}개 파일)") }
        if (m.sliceEdges.isNotEmpty()) {
            appendLine("슬라이스 간 의존:")
            m.sliceEdges.forEach { appendLine("  ${it.from} → ${it.to} (${it.files}개 파일)") }
        }
        appendLine()
        appendLine("규칙 후보 ${m.rules.size}개 (모두 사용자 승인 전):")
        m.rules.groupBy { it.kind }.forEach { (kind, rules) ->
            appendLine("  [$kind] ${rules.size}개")
            rules.forEach { appendLine("    - (${it.proposedSeverity}) ${it.description}") }
        }
        if (m.notes.isNotEmpty()) {
            appendLine()
            appendLine("참고:")
            m.notes.forEach { appendLine("  ! $it") }
        }
        if (selfCheck != null) {
            appendLine()
            appendLine("자기 검증(생성한 규칙을 현재 코드에 적용): 위반 ${selfCheck.size}건")
            selfCheck.take(10).forEach { appendLine("  ! ${it.ruleId} ${it.file}:${it.line} ${it.message}") }
        }
    }

    fun rules(m: ArchitectureModel): String = buildString {
        appendLine("규칙 ${m.rules.size}개 · 승인 ${m.rules.count { it.approved }} · 거절 ${m.rules.count { it.rejected }} · 미결정 ${m.rules.count { !it.approved && !it.rejected }}")
        m.rules.forEach {
            val state = if (it.approved) "승인" else if (it.rejected) "거절" else "후보"
            val ai = it.review?.let { r -> " AI:${r.verdict}${if (r.reason.isBlank()) "" else " - ${r.reason}"}" }.orEmpty()
            appendLine("  [$state] ${it.id} (${it.proposedSeverity})$ai")
        }
        m.review?.let { r ->
            r.applicabilityOpinion?.let { appendLine("AI 의견(적용 가능성): $it") }
            if (r.missingRules.isNotEmpty()) { appendLine("AI 제안(누락 가능 규칙):"); r.missingRules.forEach { appendLine("  ? $it") } }
        }
    }

    fun triage(m: ArchitectureModel, projectId: String): String = buildString {
        val auto = m.rules.filter { it.decidedBy == "ai-review" || it.decidedBy == "auto" }
        val user = m.rules.count { it.decidedBy == "user" }
        val pending = m.rules.filter { !it.approved && !it.rejected }
        appendLine("규칙 ${m.rules.size}개: 자동 승인 ${auto.count { it.approved }}(차단 ${auto.count { it.approved && it.proposedSeverity == "BLOCK" }}) · 자동 거절 ${auto.count { it.rejected }} · 사용자 결정 ${user} · 판정 없음 ${pending.size}")
        appendLine("기존 코드가 이미 어기는 규칙은 검토로 낮춰 승인했습니다. 바꾸려면 approve·reject $projectId <id>")
        if (pending.isNotEmpty()) {
            appendLine("AI 판정이 없어 꺼 둔 규칙:")
            pending.forEach { appendLine("  [${it.review?.verdict ?: "의견 없음"}] ${it.id} (${it.proposedSeverity}) ${it.review?.reason.orEmpty()}") }
        }
    }

    fun violations(vs: List<Violation>): String = buildString {
        if (vs.isEmpty()) { appendLine("위반 없음"); return@buildString }
        appendLine("위반 ${vs.size}건 (BLOCK ${vs.count { it.severity == "BLOCK" }}, REVIEW ${vs.count { it.severity == "REVIEW" }})")
        vs.forEach { appendLine("  [${it.severity}] ${it.file}:${it.line}  ${it.message}  <${it.ruleId}>") }
    }
}
