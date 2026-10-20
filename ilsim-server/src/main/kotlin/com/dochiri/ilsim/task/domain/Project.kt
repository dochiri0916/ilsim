package com.dochiri.ilsim.task.domain

import java.time.Instant

data class Project(val id: String, val name: String, val runnerId: String?)

data class ProjectStatus(val project: Project, val lastSeen: Instant?, val online: Boolean)
