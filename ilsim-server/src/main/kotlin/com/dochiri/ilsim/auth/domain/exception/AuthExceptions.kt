package com.dochiri.ilsim.auth.domain.exception

class InvalidAccountException(message: String) : IllegalArgumentException(message)

class SetupAlreadyDoneException : IllegalStateException("이미 계정이 있습니다. 로그인한 뒤 계정을 추가하세요")

class DuplicateUsernameException : IllegalStateException("이미 있는 아이디입니다")

class LoginLockedException : IllegalStateException("로그인 시도가 너무 많습니다. 잠시 후 다시 시도하세요")
