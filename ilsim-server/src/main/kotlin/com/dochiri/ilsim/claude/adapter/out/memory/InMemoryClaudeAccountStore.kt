package com.dochiri.ilsim.claude.adapter.out.memory

import com.dochiri.ilsim.claude.application.port.out.ClaudeAccountStore
import com.dochiri.ilsim.claude.domain.ClaudeAccount
import com.dochiri.ilsim.claude.domain.LoginSession
import java.util.concurrent.ConcurrentHashMap

class InMemoryClaudeAccountStore : ClaudeAccountStore {
    private val accounts = ConcurrentHashMap<String, List<ClaudeAccount>>()
    private val logins = ConcurrentHashMap<String, LoginSession>()
    private val removals = ConcurrentHashMap<String, MutableSet<String>>()

    override fun accounts(): List<ClaudeAccount> = accounts.values.flatten()

    override fun replaceAccounts(runnerId: String, accounts: List<ClaudeAccount>) {
        this.accounts[runnerId] = accounts.filter { it.name !in removals[runnerId].orEmpty() }
    }

    override fun logins(): List<LoginSession> = logins.values.toList()

    override fun login(id: String): LoginSession? = logins[id]

    override fun saveLogin(session: LoginSession) {
        logins[session.id] = session
    }

    override fun addRemoval(runnerId: String, name: String) {
        removals.computeIfAbsent(runnerId) { ConcurrentHashMap.newKeySet() }.add(name)
    }

    override fun takeRemovals(runnerId: String): List<String> = removals.remove(runnerId)?.toList().orEmpty()
}
