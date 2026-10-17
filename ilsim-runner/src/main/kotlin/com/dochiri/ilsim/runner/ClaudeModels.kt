package com.dochiri.ilsim.runner

/**
 * 사용하는 Claude 모델과 추론 강도. 서버가 정한 모델과 같은 5.5 세대 ID 로 고정한다(별칭은 최신 모델로 바뀔 수 있어 쓰지 않는다).
 * 검토·점검처럼 Runner 가 직접 한 번 부르는 호출은 [REVIEW] 모델과 [EFFORT] 로 실행한다.
 */
object ClaudeModels {
    const val HAIKU = "claude-haiku-5-5"
    const val SONNET = "claude-sonnet-5-5"
    const val OPUS = "claude-opus-5-5"
    const val EFFORT = "medium"
    const val REVIEW = SONNET
}
