package com.dochiri.ilsim.auth.adapter.out.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional

interface AccountJpaRepository : JpaRepository<AccountEntity, Long> {

    fun findByUsername(username: String): AccountEntity?

    @Transactional
    @Modifying
    @Query(
        value = "UPDATE accounts SET password_hash = :hash, version = version + 1 WHERE username = :username",
        nativeQuery = true,
    )
    fun updatePasswordHash(@Param("username") username: String, @Param("hash") hash: String): Int
}
