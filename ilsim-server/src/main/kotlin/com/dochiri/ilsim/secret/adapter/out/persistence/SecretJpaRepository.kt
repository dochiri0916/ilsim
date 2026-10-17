package com.dochiri.ilsim.secret.adapter.out.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface SecretJpaRepository : JpaRepository<SecretEntity, String>
