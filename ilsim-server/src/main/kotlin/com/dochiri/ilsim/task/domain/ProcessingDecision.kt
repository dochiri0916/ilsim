package com.dochiri.ilsim.task.domain

data class ProcessingDecision(val id: String, val purpose: String, val handler: String, val reason: String, val evidenceHash: String, val policyVersion: String)
