package com.dochiri.ilsim.claude.application.port.`in`

import com.dochiri.ilsim.claude.domain.ClaudeAccount
import com.dochiri.ilsim.claude.domain.ClaudeEnvironment
import com.dochiri.ilsim.claude.domain.LoginSession

/** 웹: 공통 설정 편집, 계정 로그인 시작·코드 입력·삭제. Runner: 설정 받기, 계정 보고, 로그인·삭제 일감 처리. */
interface ClaudeUseCase {
    fun environment(): ClaudeEnvironment

    fun saveEnvironment(claudeMd: String?, settingsJson: String?): ClaudeEnvironment

    fun accounts(): List<ClaudeAccount>

    fun logins(): List<LoginSession>

    fun startLogin(runnerId: String, name: String?): LoginSession

    fun submitCode(loginId: String, code: String?): LoginSession

    fun removeAccount(runnerId: String, name: String)

    fun reportAccounts(runnerId: String, accounts: List<ClaudeAccount>)

    /** 이 Runner 에 요청된 로그인 하나를 가져간다. 없으면 null. */
    fun claimLogin(runnerId: String): LoginSession?

    fun openLogin(loginId: String, url: String?): LoginSession

    /** 사용자가 입력한 코드를 한 번만 넘긴다. 아직 없으면 null. */
    fun takeCode(loginId: String): String?

    fun finishLogin(loginId: String, loggedIn: Boolean, email: String?, plan: String?, error: String?): LoginSession

    fun claimRemovals(runnerId: String): List<String>
}
