package com.dochiri.ilsim.claude.application.port.out

import com.dochiri.ilsim.claude.domain.ClaudeAccount
import com.dochiri.ilsim.claude.domain.ClaudeEnvironment
import com.dochiri.ilsim.claude.domain.LoginSession

interface ClaudeEnvironmentStore {
    fun load(): ClaudeEnvironment?

    fun save(environment: ClaudeEnvironment)
}

interface ClaudeAccountStore {
    fun accounts(): List<ClaudeAccount>

    fun replaceAccounts(runnerId: String, accounts: List<ClaudeAccount>)

    fun logins(): List<LoginSession>

    fun login(id: String): LoginSession?

    fun saveLogin(session: LoginSession)

    fun addRemoval(runnerId: String, name: String)

    fun takeRemovals(runnerId: String): List<String>
}
