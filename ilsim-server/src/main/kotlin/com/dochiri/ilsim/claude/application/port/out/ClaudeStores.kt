package com.dochiri.ilsim.claude.application.port.out

import com.dochiri.ilsim.claude.domain.ClaudeAccount
import com.dochiri.ilsim.claude.domain.ClaudeEnvironment
import com.dochiri.ilsim.claude.domain.LoginSession

/** 일심 공통 Claude 설정. 서버를 다시 띄워도 남아야 하므로 DB 에 둔다. */
interface ClaudeEnvironmentStore {
    fun load(): ClaudeEnvironment?

    fun save(environment: ClaudeEnvironment)
}

/** Runner 가 보고한 계정과 진행 중인 로그인·삭제 일감. Runner 가 다시 보고하므로 메모리에 둔다. */
interface ClaudeAccountStore {
    fun accounts(): List<ClaudeAccount>

    fun replaceAccounts(runnerId: String, accounts: List<ClaudeAccount>)

    fun logins(): List<LoginSession>

    fun login(id: String): LoginSession?

    fun saveLogin(session: LoginSession)

    fun addRemoval(runnerId: String, name: String)

    /** 가져가면 목록에서 빠진다. */
    fun takeRemovals(runnerId: String): List<String>
}
