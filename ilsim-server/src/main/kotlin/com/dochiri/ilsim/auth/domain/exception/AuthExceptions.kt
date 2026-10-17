package com.dochiri.ilsim.auth.domain.exception

class InvalidAccountException(message: String) : IllegalArgumentException(message)

class LoginLockedException : IllegalStateException("로그인 시도가 너무 많습니다. 잠시 후 다시 시도하세요")
