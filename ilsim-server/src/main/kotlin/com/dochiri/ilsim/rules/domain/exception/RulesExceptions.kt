package com.dochiri.ilsim.rules.domain.exception

class InvalidRuleRequestException(message: String) : IllegalArgumentException(message)

class AnalysisInProgressException(projectId: String) : IllegalStateException("이미 진행 중인 분석이 있습니다: $projectId")
