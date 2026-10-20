package com.dochiri.ilsim.claude.application.usecase

import com.dochiri.ilsim.claude.application.port.`in`.ClaudeUseCase
import com.dochiri.ilsim.claude.application.port.out.ClaudeAccountStore
import com.dochiri.ilsim.claude.application.port.out.ClaudeEnvironmentStore
import com.dochiri.ilsim.claude.application.port.out.ClaudeSkillStore
import com.dochiri.ilsim.claude.domain.ClaudeAccount
import com.dochiri.ilsim.claude.domain.ClaudeEnvironment
import com.dochiri.ilsim.claude.domain.ClaudeSkill
import com.dochiri.ilsim.claude.domain.LoginSession
import com.dochiri.ilsim.claude.domain.LoginStatus
import com.dochiri.ilsim.claude.domain.exception.InvalidClaudeRequestException
import java.time.Clock
import java.time.Duration
import java.util.UUID

class ClaudeService(
    private val environments: ClaudeEnvironmentStore,
    private val skillStore: ClaudeSkillStore,
    private val store: ClaudeAccountStore,
    private val isJsonObject: (String) -> Boolean,
    private val clock: Clock = Clock.systemUTC(),
) : ClaudeUseCase {

    override fun environment(): ClaudeEnvironment = (environments.load() ?: ClaudeEnvironment()).withSkills(skillStore.all())

    override fun saveEnvironment(claudeMd: String?, settingsJson: String?): ClaudeEnvironment =
        ClaudeEnvironment.of(claudeMd, settingsJson, isJsonObject, clock.instant()).also(environments::save).withSkills(skillStore.all())

    override fun skills(): List<ClaudeSkill> = skillStore.all().sortedBy { it.name }

    @Synchronized
    override fun saveSkill(name: String?, files: Map<String, String>?): ClaudeSkill =
        ClaudeSkill.of(name, files, clock.instant()).also(skillStore::save)

    @Synchronized
    override fun removeSkill(name: String) {
        if (!skillStore.delete(ClaudeSkill.name(name))) throw InvalidClaudeRequestException("스킬이 없습니다: $name")
    }

    override fun accounts(): List<ClaudeAccount> = store.accounts().sortedWith(compareBy({ it.runnerId }, { it.name }))

    override fun logins(): List<LoginSession> {
        val now = clock.instant()
        store.logins().filter { it.active && Duration.between(it.requestedAt, now) > LOGIN_TTL }
            .forEach { store.saveLogin(it.finish(false, null, null, "시간이 지나 로그인을 취소했습니다")) }
        return store.logins().sortedByDescending { it.requestedAt }
    }

    @Synchronized
    override fun startLogin(runnerId: String, name: String?): LoginSession {
        val runner = runnerId.trim().ifEmpty { throw InvalidClaudeRequestException("Runner id 가 비어 있습니다") }
        val account = ClaudeAccount.name(name)
        if (logins().any { it.active && it.runnerId == runner && it.name == account }) throw InvalidClaudeRequestException("이미 로그인을 진행 중입니다: $account")
        return LoginSession(UUID.randomUUID().toString(), runner, account, LoginStatus.REQUESTED, clock.instant()).also(store::saveLogin)
    }

    @Synchronized
    override fun submitCode(loginId: String, code: String?): LoginSession = session(loginId).submit(code).also(store::saveLogin)

    @Synchronized
    override fun removeAccount(runnerId: String, name: String) {
        val account = ClaudeAccount.name(name)
        store.addRemoval(runnerId.trim(), account)
        store.replaceAccounts(runnerId.trim(), store.accounts().filter { it.runnerId == runnerId.trim() && it.name != account })
    }

    @Synchronized
    override fun reportAccounts(runnerId: String, accounts: List<ClaudeAccount>) {
        val now = clock.instant()
        store.replaceAccounts(runnerId, accounts.map { it.copy(runnerId = runnerId, name = ClaudeAccount.name(it.name), reportedAt = now) })
    }

    @Synchronized
    override fun claimLogin(runnerId: String): LoginSession? =
        logins().filter { it.runnerId == runnerId && it.status == LoginStatus.REQUESTED }.minByOrNull { it.requestedAt }

    @Synchronized
    override fun openLogin(loginId: String, url: String?): LoginSession {
        val link = url?.trim().orEmpty()
        if (!link.startsWith("https://")) throw InvalidClaudeRequestException("로그인 URL 이 올바르지 않습니다")
        return session(loginId).open(link).also(store::saveLogin)
    }

    @Synchronized
    override fun takeCode(loginId: String): String? {
        val current = session(loginId)
        val code = current.code ?: return null
        store.saveLogin(current.takeCode())
        return code
    }

    @Synchronized
    override fun finishLogin(loginId: String, loggedIn: Boolean, email: String?, plan: String?, error: String?): LoginSession =
        session(loginId).finish(loggedIn, email, plan, error).also(store::saveLogin)

    @Synchronized
    override fun claimRemovals(runnerId: String): List<String> = store.takeRemovals(runnerId)

    private fun session(id: String): LoginSession = store.login(id) ?: throw InvalidClaudeRequestException("로그인 요청이 없습니다: $id")

    private companion object {
        val LOGIN_TTL: Duration = Duration.ofMinutes(15)
    }
}
