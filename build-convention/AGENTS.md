# Build Convention Repository Guidance

이 저장소는 Java/Spring 프로젝트의 아키텍처, 코드 품질, 테스트 규칙을 결정론적으로 검증하는 Gradle Convention Plugin이다.

이 문서는 **Build Convention 플러그인 자체를 개발하거나 수정할 때** 적용한다.

소비 프로젝트가 따라야 하는 Java/Spring 상세 규칙은 다음 문서를 기준으로 한다.

- `docs/conventions/README.md`
- 변경 계층과 기술에 해당하는 `docs/conventions/` 상세 문서

실제 validation rule 구현과 테스트가 최종 source of truth다.

## Modules

- `core/`: 언어와 무관한 확장·카탈로그·검증(ArchUnit, migration, rule ID). 언어별 모듈을 참조하지 않는다.
- `java/`: Java 소스 검증, Checkstyle/PMD/SpotBugs 배선, anti-bypass, report. 플러그인 ID는 `io.github.dochiri0916.build-convention.java`다.
- `kotlin/`: Kotlin PSI 소스 기반 도메인·VO·일급 컬렉션 검증, 공통 ArchUnit 배선, anti-bypass와 report. 세부 기준은 `docs/conventions/kotlin-domain-conventions.md`다.
- 루트: 기존 ID `io.github.dochiri0916.build-convention`(Java 컨벤션 적용)과 `ai`가 읽는 지침 묶음·버전. 산출물 좌표와 JAR 자원 경로를 유지한다.

커버리지 기준은 모듈마다 따로 적용한다. 다른 모듈의 테스트로 기준을 채우지 않는다.
`docs/conventions/coverage.json`의 경로는 이 디렉터리 기준(`java/src/...`, `core/src/...`)으로 쓴다.

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

패키지는 `io.github.dochiri0916.buildconvention` 아래에서 종류와 검증 책임으로 나눈다.

- `plugin`, `integrity`, `report`: Gradle 연결·무력화 방지·보고서.
- `source.java`, `source.kotlin`: 언어별 소스 수집·파싱·AST와 타입 분석.
- `extension`, `catalog`, `context`, `coverage`: 언어 중립 설정·카탈로그·BC·커버리지 입력 도구.
- `validator`: `architecture`, `domain`(aggregate·vo·collection·factory), `application`, `adapter`(web·persistence), `exception`, `messaging`, `migration`, `quality`, `review`별 검증.

테스트도 검증 책임에 대응하는 패키지에 둔다. 패키지를 옮기면 import·플러그인 implementationClass·`docs/conventions/coverage.json`의 구현 및 테스트 근거 경로를 함께 갱신한다.

## Documentation

소비 프로젝트에 영향을 주는 규칙 의미가 바뀌면 관련 문서를 함께 갱신한다.

특히 다음 변경은 `docs/conventions/`의 해당 규칙과 안내 반영 여부를 확인한다.

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
규칙이 바뀌면 `docs/templates`도 새 규칙으로 전체 `check`를 통과하는지 확인한다. ask가 이 틀을 모델에 예시로 주고 `global` 파일을 프로젝트에 복사한다.
