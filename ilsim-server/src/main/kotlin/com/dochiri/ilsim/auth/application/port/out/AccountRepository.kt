package com.dochiri.ilsim.auth.application.port.out

import com.dochiri.ilsim.auth.domain.Account

/** 계정은 관리자가 DB 에 직접 만든다. 여기서는 읽기와 비밀번호 변경만 한다. */
interface AccountRepository {
    fun findByUsername(username: String): Account?

    /** 비밀번호 해시를 바꾼다. 그 아이디가 없으면 false. */
    fun updatePasswordHash(username: String, passwordHash: String): Boolean
}
