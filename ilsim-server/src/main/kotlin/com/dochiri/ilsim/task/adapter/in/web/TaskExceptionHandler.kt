package com.dochiri.ilsim.task.adapter.`in`.web

import com.dochiri.ilsim.task.domain.exception.InvalidTaskRequestException
import com.dochiri.ilsim.task.domain.exception.InvalidTaskStateException
import com.dochiri.ilsim.task.domain.exception.TaskNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice(basePackageClasses = [RunnerController::class])
class TaskExceptionHandler {

    @ExceptionHandler(InvalidTaskRequestException::class, InvalidTaskStateException::class)
    fun badRequest(e: RuntimeException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.badRequest().body(mapOf("error" to e.message))

    @ExceptionHandler(TaskNotFoundException::class)
    fun notFound(e: TaskNotFoundException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to e.message))
}
