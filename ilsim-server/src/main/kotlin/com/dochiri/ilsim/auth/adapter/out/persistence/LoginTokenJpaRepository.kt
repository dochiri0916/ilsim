package com.dochiri.ilsim.auth.adapter.out.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional

interface LoginTokenJpaRepository : JpaRepository<LoginTokenEntity, String> {

    @Transactional
    @Modifying
    @Query(value = "DELETE FROM login_tokens WHERE username = :username", nativeQuery = true)
    fun deleteByUsername(@Param("username") username: String): Int

    @Transactional
    @Modifying
    @Query(value = "DELETE FROM login_tokens WHERE expires_at < :now", nativeQuery = true)
    fun deleteExpired(@Param("now") now: java.time.Instant): Int
}
