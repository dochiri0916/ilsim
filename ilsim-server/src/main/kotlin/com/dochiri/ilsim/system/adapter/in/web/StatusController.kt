package com.dochiri.ilsim.system.adapter.`in`.web

import java.time.Clock
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class StatusController(private val clock: Clock) {

    @GetMapping("/api/status")
    fun status(): Map<String, String> =
        mapOf("name" to "ilsim-server", "version" to "0.0.1", "time" to clock.instant().toString())
}
