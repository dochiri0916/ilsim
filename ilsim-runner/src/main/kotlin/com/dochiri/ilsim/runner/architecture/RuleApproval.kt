package com.dochiri.ilsim.runner.architecture

object RuleApproval {

    fun approve(model: ArchitectureModel, ids: Collection<String>): ArchitectureModel = update(model, ids) { it.copy(approved = true, rejected = false, decidedBy = "user") }

    fun reject(model: ArchitectureModel, ids: Collection<String>): ArchitectureModel = update(model, ids) { it.copy(approved = false, rejected = true, decidedBy = "user") }

    fun keepIds(model: ArchitectureModel): List<String> =
        model.rules.filter { it.review?.verdict == "KEEP" && !it.approved && !it.rejected }.map { it.id }

    fun autoApprove(model: ArchitectureModel, unstable: Set<String> = emptySet()): ArchitectureModel = model.copy(rules = model.rules.map {
        if (it.decidedBy != null && it.decidedBy != "ai-review") it
        else when (it.review?.verdict) {
            "KEEP" -> it.copy(approved = true, rejected = false, decidedBy = "ai-review", proposedSeverity = if (it.id in unstable) "REVIEW" else it.proposedSeverity)
            "SUSPECT" -> it.copy(approved = false, rejected = true, decidedBy = "ai-review")
            else -> it.copy(approved = false, rejected = false, decidedBy = null)
        }
    })

    fun keepUserDecisions(saved: ArchitectureModel?, model: ArchitectureModel): ArchitectureModel {
        val user = saved?.rules?.filter { it.decidedBy == "user" }?.associateBy { it.id }.orEmpty()
        return model.copy(rules = model.rules.map { r ->
            user[r.id]?.let { r.copy(approved = it.approved, rejected = it.rejected, decidedBy = it.decidedBy) } ?: r
        })
    }

    fun carryOver(previous: ArchitectureModel?, mined: ArchitectureModel): ArchitectureModel {
        if (previous == null) return mined
        return mined.copy(
            rules = mined.rules.map { r ->
                previous.rules.firstOrNull { it.id == r.id && it.kind == r.kind && it.params == r.params }
                    ?.let { o -> if (o.decidedBy == "auto" || o.decidedBy == "ai-review" || (r.decidedBy == "auto" && o.decidedBy != "user")) r.copy(review = o.review) else r.copy(approved = o.approved, rejected = o.rejected, decidedBy = o.decidedBy, review = o.review) } ?: r
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
