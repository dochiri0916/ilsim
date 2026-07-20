# Testing Convention

## 흐름

기능, 버그 수정, 비즈니스 규칙 변경은 다음 순서로 진행한다.

1. 정상·실패·경계·상태 전이·ErrorCode·외부 Port 실패를 설계한다.
2. 실패하는 테스트를 먼저 작성하고 대상 테스트를 실행한다.
3. 최소 구현으로 통과시킨다.
4. 리팩터링 후 대상 테스트와 전체 검증을 실행한다.

## 계층별 테스트

| 계층 | 검증 대상 |
|---|---|
| Domain | Mock 없이 VO 불변식, Aggregate 상태 전이, ErrorCode |
| Application | Port mock/fake를 통한 UseCase 흐름과 결과 |
| Persistence Adapter | Port 계약, Entity-Domain mapping, migration 제약 |
| Web Adapter | 요청 검증, UseCase 호출 계약, ProblemDetail 응답 |
| Architecture | build-convention과 ArchUnit 규칙 |

## 테스트 작성 규칙

- JUnit 테스트 메서드는 자연스러운 한국어 `@DisplayName`을 둔다.
- 본문은 `// given`, `// when`, `// then` 순서로 작성한다. 예외 assertion은 `// when & then`을 사용할 수 있다.
- 정상 흐름만이 아니라 null, blank, 경계값, 중복, 권한, 허용되지 않는 상태 전이를 검증한다.
- 예외 테스트는 예외 타입, ErrorCode, 필요한 실패 맥락을 검증한다.
- `assertDoesNotThrow`만 사용하거나 mock verify만 수행하는 테스트를 작성하지 않는다.
- `@Disabled`, assumption, 넓은 예외 catch로 실패를 숨기지 않는다.

## 필수 검증

Architecture violation은 메시지 앞의 안정적인 `[ARCH-...]` rule ID로 분류한다. 메시지 문구는 개선될 수 있으므로 테스트와 CI 리포트는 가능하면 rule ID를 기준으로 집계한다.

Production, 테스트, build 설정, migration을 변경한 경우 완료 전에 실행한다.

```bash
./gradlew check
```

변경 코드 커버리지를 확인해야 하는 경우에는 다음을 실행한다.

```bash
./gradlew check -PchangedCoverageBaseRef=origin/main
```

검증 실패를 해결하기 위해 품질 태스크, 테스트, 커버리지 기준, 입력 범위를 비활성화하거나 제외하지 않는다.
