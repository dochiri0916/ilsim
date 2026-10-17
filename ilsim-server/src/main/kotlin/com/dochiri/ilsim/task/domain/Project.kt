package com.dochiri.ilsim.task.domain

import java.time.Instant

/** Runner 가 보고한 프로젝트. */
data class Project(val id: String, val name: String, val runnerId: String?)

/** Runner 가 최근에 poll 했는지까지 포함한 프로젝트 상태. */
data class ProjectStatus(val project: Project, val lastSeen: Instant?, val online: Boolean)
