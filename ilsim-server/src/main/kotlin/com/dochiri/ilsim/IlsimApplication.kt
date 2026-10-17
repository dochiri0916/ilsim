package com.dochiri.ilsim

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class IlsimApplication

fun main(args: Array<String>) {
    runApplication<IlsimApplication>(*args)
}
