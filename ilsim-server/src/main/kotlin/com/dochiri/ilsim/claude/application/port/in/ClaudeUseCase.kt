package com.dochiri.ilsim.claude.application.port.`in`

import com.dochiri.ilsim.claude.domain.ClaudeAccount
import com.dochiri.ilsim.claude.domain.ClaudeEnvironment
import com.dochiri.ilsim.claude.domain.ClaudeSkill
import com.dochiri.ilsim.claude.domain.LoginSession

interface ClaudeUseCase {
    fun environment(): ClaudeEnvironment

    fun saveEnvironment(claudeMd: String?, settingsJson: String?): ClaudeEnvironment

    fun skills(): List<ClaudeSkill>

    fun saveSkill(name: String?, files: Map<String, String>?): ClaudeSkill

    fun removeSkill(name: String)

    fun accounts(): List<ClaudeAccount>

    fun logins(): List<LoginSession>

    fun startLogin(runnerId: String, name: String?): LoginSession

    fun submitCode(loginId: String, code: String?): LoginSession

    fun removeAccount(runnerId: String, name: String)

    fun reportAccounts(runnerId: String, accounts: List<ClaudeAccount>)

    fun claimLogin(runnerId: String): LoginSession?

    fun openLogin(loginId: String, url: String?): LoginSession

    fun takeCode(loginId: String): String?

    fun finishLogin(loginId: String, loggedIn: Boolean, email: String?, plan: String?, error: String?): LoginSession

    fun claimRemovals(runnerId: String): List<String>
}
