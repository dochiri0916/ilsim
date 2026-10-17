package com.dochiri.ilsim.task.domain.exception

class InvalidTaskRequestException(message: String) : IllegalArgumentException(message)

class InvalidTaskStateException(message: String) : IllegalStateException(message)

class TaskNotFoundException(id: String) : NoSuchElementException("작업 없음: $id")
