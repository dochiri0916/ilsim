package com.dochiri.ilsim.auth.adapter.out.persistence

import java.time.Instant
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional

interface AccountJpaRepository : JpaRepository<AccountEntity, Long> {

    fun findByUsername(username: String): AccountEntity?

    fun findAllByOrderByIdAsc(): List<AccountEntity>

    /** 계정이 하나도 없을 때만 넣는다. 들어간 행 수를 돌려준다. */
    @Transactional
    @Modifying
    @Query(
        value = """
            INSERT INTO accounts (version, username, password_hash, created_at)
            SELECT 0, :username, :hash, :now FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM accounts)""",
        nativeQuery = true,
    )
    fun insertFirst(@Param("username") username: String, @Param("hash") hash: String, @Param("now") now: Instant): Int
}
