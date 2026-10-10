# 소비 프로젝트 컨벤션 안내

이 디렉터리는 Java 21+/Spring Boot/JPA와 Kotlin/JVM 소비 프로젝트의 컨벤션 기준이다. Kotlin 도메인 변경에는 먼저 [Kotlin 도메인·VO 기준](kotlin-domain-conventions.md)을 읽고 `templates/kotlin/src/main/kotlin`의 기존 예시를 참고한다. Java 전용 문법·품질 도구 규칙을 Kotlin에 적용하지 않는다. 기존 규칙을 우선하며 다른 문서에서 가져온 내용은 누락된 세부 사항만 보완한다. 명시적인 사용자 요청·대화에서 합의한 결정, 프로젝트의 `AGENTS.md`와 기존 구조를 먼저 확인한다. 작은 변경을 위해 요청 범위 밖 구조 개편이나 기존 코드의 일괄 마이그레이션을 하지 않는다.

작업 전 다음 순서로 필요한 문서를 읽는다.

1. [패키지 구조](package-structure.md), [공통 코드](coding-conventions.md), [생활 체조](object-calisthenics.md)
2. 변경하는 계층의 [Domain](domain-conventions.md), [Application](application-conventions.md), [Adapter](adapter-conventions.md)
3. 관련 기술의 [예외](exception-conventions.md), [영속성](persistence-conventions.md), [Querydsl](querydsl-conventions.md), [DB 변경](database-migration-conventions.md)
4. 메시지·이벤트 변경 시 [BC 이벤트](bounded-context-events.md), [Outbox/Inbox](outbox-inbox-conventions.md)
5. [테스트](test-conventions.md), [검증 절차](validation-conventions.md)

구조 예시는 `tool-test-lab`이며 파일 단위 코드 예시는 [템플릿](../templates/README.md)에 있다. 템플릿의 실행 조건과 검증 범위를 확인하고 필요한 예시만 참고한다. 템플릿을 실제 소스 디렉터리에 일괄 복사하지 않는다.

[coverage.json](coverage.json)은 규칙별 구현·검증 근거와 pending/review 항목을 연결한다. 적용 기록은 [CONVENTION_ROLLOUT](../CONVENTION_ROLLOUT.md)에 있다. 정적 검사의 PASS가 업무 불변식·운영 안전성을 증명하지는 않으며, 문서에 없는 구조가 모두 자동 거부된다는 뜻도 아니다. 실제 검증 규칙의 구현과 테스트를 함께 확인한다.

`ai`는 이 문서와 상세 규칙·템플릿을 검사에 사용하는 로컬 플러그인 JAR에서 배치한다. `docs/build-convention/.ai-guidance.json`의 버전을 확인하고, 프로젝트가 별도 플러그인 버전을 적용하면 일치 여부를 확인한다.

실제 DB·브로커·HTTP 동작과 기본/safe 구현의 계약 일치는 [실행 의미 검토](runtime-verification.md)를 따른다. `conventionReview=REVIEW`와 `EXECUTED` 테스트 근거를 구조 검사 PASS와 구분한다.
