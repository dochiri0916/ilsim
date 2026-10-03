# Build Convention Repository Guidance

이 저장소는 Java/Spring 프로젝트의 아키텍처, 코드 품질, 테스트 규칙을 결정론적으로 검증하는 Gradle Convention Plugin이다.

이 문서는 **Build Convention 플러그인 자체를 개발하거나 수정할 때** 적용한다.

소비 프로젝트가 따라야 하는 Java/Spring 상세 규칙은 다음 문서를 기준으로 한다.

- `docs/AGENTS_GUIDANCE.md`
- `docs/ARCHITECTURE.md`
- `docs/ERROR_HANDLING.md`
- `docs/TESTING.md`

실제 validation rule 구현과 테스트가 최종 source of truth다.

## Rule Development

새 validation rule을 추가하거나 기존 rule을 변경할 때 다음을 함께 검토한다.

- 실제 rule 구현
- positive fixture
- negative fixture
- regression test
- anti-bypass test
- machine-readable report
- 관련 문서

규칙 하나를 추가하면서 다른 규칙을 임의로 약화하지 않는다.

## Rule Integrity

검증 실패를 해결하기 위해 다음 행동을 하지 않는다.

- 기존 rule 제거
- 기존 rule 완화
- 테스트 삭제
- assertion 약화
- validation task skip
- `ignoreFailures` 활성화
- coverage 기준 하향
- mutation 기준 하향
- architecture rule 완화
- 임의의 production class exclusion
- 특정 fixture만 통과시키기 위한 예외 처리

공식 exclusion이 필요한 경우 명시적인 규칙과 테스트로 표현한다.

## Anti-Bypass

사용자가 Build Convention 검증을 우회할 수 없도록 기존 anti-bypass 정책을 유지한다.

다음을 우회 수단으로 허용하지 않는다.

- validation task disable
- source set 임의 제외
- JaCoCo 대상 임의 축소
- PMD / Checkstyle / SpotBugs ignore
- architecture validation 제거
- mutation validation 우회

공식적으로 허용되는 제외는 Build Convention 내부 규칙으로 정의하고 테스트한다.

## Report

machine-readable report는 외부 도구가 사용할 수 있는 contract로 취급한다.

다음을 불필요하게 깨뜨리지 않는다.

- schema
- check identifier
- status 의미
- coverage 정보
- mutation 정보

report 변경이 필요하면 기존 consumer 영향을 검토하고 테스트를 함께 수정한다.

## Architecture

Convention 내부에서도 책임을 분리한다.

- validation rule
- Gradle plugin/task wiring
- report generation
- anti-bypass
- fixture/test support

기존 helper나 abstraction으로 해결할 수 있으면 중복 구현을 만들지 않는다.

## Documentation

소비 프로젝트에 영향을 주는 규칙 의미가 바뀌면 관련 문서를 함께 갱신한다.

특히 다음 변경은 `docs/AGENTS_GUIDANCE.md` 반영 여부를 확인한다.

- Domain / Application / Adapter 규칙
- package 규칙
- transaction 규칙
- persistence 규칙
- exception 규칙
- testing 규칙
- validation 우회 금지 규칙

## Tests

새 rule은 가능한 경우 최소 다음을 포함한다.

- 허용되는 코드가 PASS하는 테스트
- 금지되는 코드가 FAIL하는 테스트
- 우회 시도가 FAIL하는 테스트

버그 수정에는 해당 버그를 재현하는 regression test를 추가한다.

## Completion

완료 전에 실행한다.

```bash
./gradlew clean check
```

실패하면 검증을 우회하지 말고 실제 원인을 수정한다.