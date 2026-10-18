package com.dochiri.ilsim.runner.architecture

/** 규칙 후보의 승인·거절. 모델을 바꾸지 않고 새 모델을 돌려준다. */
object RuleApproval {

    fun approve(model: ArchitectureModel, ids: Collection<String>): ArchitectureModel = update(model, ids) { it.copy(approved = true, rejected = false, decidedBy = "user") }

    fun reject(model: ArchitectureModel, ids: Collection<String>): ArchitectureModel = update(model, ids) { it.copy(approved = false, rejected = true, decidedBy = "user") }

    /** AI 검토가 KEEP 으로 본 규칙 중 아직 판단하지 않은 것의 id. */
    fun keepIds(model: ArchitectureModel): List<String> =
        model.rules.filter { it.review?.verdict == "KEEP" && !it.approved && !it.rejected }.map { it.id }

    /**
     * AI 검토가 KEEP 으로 본 미결정 규칙 중 REVIEW 심각도만 승인한다(decidedBy=ai-review).
     * 게이트를 막는 BLOCK 규칙은 AI 판단만으로 켜지 않고 사용자에게 넘긴다. 사용자가 이미 정한 규칙은 건드리지 않는다.
     */
    fun autoApprove(model: ArchitectureModel): ArchitectureModel = model.copy(rules = model.rules.map {
        if (it.review?.verdict == "KEEP" && it.proposedSeverity != "BLOCK" && !it.approved && !it.rejected) it.copy(approved = true, decidedBy = "ai-review") else it
    })

    /** AI 검토가 도는 동안 사용자가 웹에서 내린 결정([saved] 의 decidedBy=user)을 [model] 에 덮어 잃지 않게 한다. */
    fun keepUserDecisions(saved: ArchitectureModel?, model: ArchitectureModel): ArchitectureModel {
        val user = saved?.rules?.filter { it.decidedBy == "user" }?.associateBy { it.id }.orEmpty()
        return model.copy(rules = model.rules.map { r ->
            user[r.id]?.let { r.copy(approved = it.approved, rejected = it.rejected, decidedBy = it.decidedBy) } ?: r
        })
    }

    /** 다시 분석한 [mined] 에 이전 모델의 결정·AI 의견을 같은 규칙(id·종류·인자가 같은 것)에 옮긴다. */
    fun carryOver(previous: ArchitectureModel?, mined: ArchitectureModel): ArchitectureModel {
        if (previous == null) return mined
        return mined.copy(
            rules = mined.rules.map { r ->
                previous.rules.firstOrNull { it.id == r.id && it.kind == r.kind && it.params == r.params }
                    ?.let { o -> r.copy(approved = o.approved, rejected = o.rejected, decidedBy = o.decidedBy, review = o.review) } ?: r
            },
            review = previous.review,
        )
    }

    fun undecidedIds(model: ArchitectureModel): List<String> = model.rules.filter { !it.approved && !it.rejected }.map { it.id }

    private fun update(model: ArchitectureModel, ids: Collection<String>, change: (ArchRule) -> ArchRule): ArchitectureModel {
        val known = model.rules.map { it.id }.toSet()
        val unknown = ids.filterNot { it in known }
        require(unknown.isEmpty()) { "알 수 없는 규칙 id: ${unknown.joinToString()}" }
        return model.copy(rules = model.rules.map { if (it.id in ids) change(it) else it })
    }
}
