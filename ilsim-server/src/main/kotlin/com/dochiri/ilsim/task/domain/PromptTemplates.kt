package com.dochiri.ilsim.task.domain

/** 단계별로 Agent 에 보내는 지시문. */
object PromptTemplates {

    fun build(task: Task, stage: Stage, feedback: String?): String {
        val f = task.feature
        val body = when (stage) {
            Stage.REQUIREMENTS -> """
                당신은 요구사항 분석가다. 아래 요구사항을 분석해 `specs/$f/requirements.md` 파일 하나만 작성하라. 다른 파일은 수정하지 마라.

                ## 요구사항 원문
                ${task.requirement}

                ## 작성 규칙
                - 기존 코드와 문서를 읽어 영향 범위를 파악한 뒤 작성한다.
                - 개별 요구사항에 REQ-1, REQ-2 ... 형식의 ID를 붙이고, 각각 검증 가능한 인수 조건을 적는다.
                - 모호하거나 누락된 정보는 추측하지 말고 `## 미결정 사항` 섹션에 질문으로 적는다.
                - `## 예외 상황`, `## 기존 기능 영향` 섹션을 포함한다.
                - 코드는 작성하지 않는다.
                """.trimIndent() + "\n"

            Stage.TEST_CASES -> """
                당신은 테스트 설계자다. `specs/$f/requirements.md`를 읽고 `specs/$f/test-spec.md` 파일 하나만 작성하라. 다른 파일은 수정하지 마라.

                ## 작성 규칙
                - 표 형식(ID | 요구사항 | 분류 | 시나리오 | 기대 결과)으로 TC-1, TC-2 ... 를 나열한다.
                - 모든 REQ-n은 최소 하나의 TC에 연결되어야 한다.
                - 분류 체크리스트: 정상, 예외, 경계값, 상태 전이, 동시성, 멱등성, 트랜잭션/롤백, 인증·인가, 기존 기능 회귀.
                - 해당 없는 분류는 `## 제외한 분류` 섹션에 사유와 함께 적는다.
                - 요구사항에서 도출 가능한 케이스를 빠짐없이 뽑는다.
                """.trimIndent() + "\n"

            Stage.TEST_CODE -> """
                당신은 테스트 작성자다. `specs/$f/test-spec.md`의 모든 TC를 자동화된 테스트 코드로 작성하라.

                ## 규칙
                - 테스트 경로의 파일만 생성·수정한다. 프로덕션 코드와 `specs/`는 수정하지 마라.
                - 테스트 이름이나 주석에 TC-ID를 넣어 추적 가능하게 한다.
                - 구현이 아직 없으므로 테스트는 실패해도 된다. 실패해야 정상이다.
                - 기존 테스트를 삭제하거나 약화하지 마라.
                """.trimIndent() + "\n"

            Stage.IMPLEMENTATION -> """
                당신은 구현 담당자다. `specs/$f/requirements.md`를 만족하도록 프로덕션 코드를 구현해 모든 테스트가 통과하게 하라.

                ## 규칙
                - 테스트 파일과 `specs/`는 수정하지 마라.
                - 테스트만 통과시키는 우회(테스트 환경 분기, 기대값 하드코딩, 테스트 전용 분기)를 하지 마라. 요구사항의 의도대로 일반적으로 구현한다.
                - 프로젝트의 기존 아키텍처와 코드 컨벤션을 따른다.
                """.trimIndent() + "\n"
        }
        if (feedback.isNullOrBlank()) return body
        return body + "\n## 이전 시도 피드백\n$feedback\n\n위 내용을 반영해 수정하라.\n"
    }
}
