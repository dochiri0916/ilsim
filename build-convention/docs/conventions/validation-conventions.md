# 검증 절차

Production·테스트·빌드 설정·migration을 변경하면 완료 전에 전체 검증을 수행한다. `ai`로 실행한 작업은 끝난 뒤 자동 검사가 실행되므로 작업 중 같은 `check`나 위험 분석을 따로 반복하지 않는다. 필요한 대상 테스트는 작업 중 실행할 수 있다.

직접 작업한 경우 Gradle 프로젝트 루트에서 실행한다.

```bash
./gradlew check
```

변경 코드 커버리지를 비교할 기준이 필요한 경우 다음처럼 Git ref를 지정한다.

```bash
./gradlew check -PchangedCoverageBaseRef=origin/main
```

멀티 모듈 저장소의 변경 코드 커버리지는 각 모듈의 source root 기준으로 계산한다. 독립 Gradle 프로젝트는 각 프로젝트의 Wrapper와 루트를 사용한다.

## 실패 처리와 리포트

- 검증 실패는 실제 코드를 수정해 해결한다. 품질 태스크·테스트·커버리지 기준·입력 범위를 비활성화하거나 제외하지 않는다.
- Architecture violation은 안정적인 `[ARCH-...]` rule ID로 분류한다. 테스트와 CI 집계는 바뀔 수 있는 메시지 문구 대신 rule ID를 기준으로 한다.
- 플러그인 내부의 `RuleIdContractTest`와 `rule-id-contract.txt`는 메시지 템플릿별 rule ID 계약을 검증한다.
- machine-readable report의 schema·check identifier·status 의미·coverage·mutation 정보는 소비 계약으로 취급한다. PASS·FAIL·SKIPPED·NOT_APPLIED 상태를 임의로 서로 바꾸지 않는다.

## 검증 우회 금지

- validation task disable, source set 임의 제외, JaCoCo 대상 축소, PMD·Checkstyle·SpotBugs의 실패 무시, architecture·mutation validation 제거를 하지 않는다.
- `test`의 JUnit Platform `includeTags`·`excludeTags`·`includeEngines`·`excludeEngines`로 검사 대상을 축소하지 않는다.
- `scanForTestClasses = false`를 설정하거나 `testClassesDirs`에서 test source set 출력을 제거하지 않는다.
- PIT `targetTests` 변경, `excludedGroups`·`includedGroups`·`includedTestMethods` 지정, `skipFailingTests = true`로 검증을 우회하지 않는다.
- 예외적 제외가 필요하면 플러그인이 공식적으로 제공하는 규칙과 테스트를 확인한다. 특정 fixture만 통과시키기 위한 예외 처리를 추가하지 않는다.
