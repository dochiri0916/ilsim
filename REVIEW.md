# Build Convention 및 `~/.codex/AGENTS.md` 리뷰

> 이 문서는 커밋 `4d164d6`과 당시 `AGENTS.md`를 기준으로 작성한 baseline 리뷰다. 이후 작업 트리에는 Application dependency allowlist와 collaborator 긍정 규칙, cross-context 직접 참조, Repository Port 시그니처 기반 Aggregate Root 식별과 같은 Context Root 직접 참조 차단, package topology fail-closed, JDK AST syntax fail-closed와 Java text block lexical parsing, Groovy package/import parsing, 누적 migration 및 외부 참조 metadata, 로컬 변경 커버리지, `adapter.in` 커버리지, Validator/TestKit 테스트, `validateArchitectureConventions` 명칭, 플러그인 자체 CodeNarc/JaCoCo 게이트가 반영되었다. Java의 package/import/type/superclass/interface/field/record component/annotation/constructor/method/transaction 선언, compact constructor, Java 테스트 method와 qualified type reference 탐색은 주요 경로에서 JDK AST와 source position으로 이전했다. 호출 표현식과 SQL 의미 검사는 여전히 휴리스틱이며, 실제 DB metadata 검증과 플러그인 자체 mutation gate도 후속 과제로 남는다.

> 수정 플러그인을 `tool-test-lab`에 적용한 전체 `check`에서는 기존에 통과하던 `ProductRepositoryPort`의 Spring Data `Page` 반환과 `ListProductsService`의 `Page` 호출 2곳을 ArchUnit이 차단했다. 총 3건의 의도된 Application 기술 의존 위반이며, 테스트 랩 소스는 변경하지 않았다.

> 1차 `validateArchitectureConventions` 단독 실행은 설정된 정책 위반 35건을 검출했다. Context 간 Application/Domain 직접 의존 31건, Mapper 내부 UUID 생성 2건, Spring Data `Page` 의존 2건이다. 최초 실프로젝트 검증에서 방향이 명확한 `toItemEntities`까지 거부하는 Mapper 이름 오탐 2건과 값 타입을 Aggregate로 단정하는 부정확한 진단을 발견했고, Red 테스트를 추가한 뒤 허용 패턴과 메시지를 보정했다.

> 후속 강화본의 최종 단독 실행은 정책 위반 43건을 검출했다. Context 경계 직접 의존 32건, Application의 SLF4J 기술 의존 6건, Mapper 내부 UUID 생성 2건, Spring Data `Page` 의존 2건, 하나의 `ProductRepositoryPort`가 `Product`와 `Category` 두 Aggregate Root를 함께 관리하는 위반 1건이다. 실제 시그니처와 소스를 대조해 신규 검출이 설정된 정책과 일치함을 확인했고, 테스트 랩 소스는 수정하지 않았다.

> AST 후속 이전본도 `tool-test-lab` 단독 검증에서 동일한 43건을 검출했다. 최초 회귀 실행에서는 javac가 record component를 member variable로도 노출하는 모델 차이 때문에 단일 값 VO와 first-class collection을 일반 필드로 중복 판정하는 오탐이 추가로 드러났다. 이를 실패 테스트로 고정하고 record component와 선언 필드를 분리한 뒤 기준선 43건으로 복귀시켰다.

> 최신 후속본은 JPA field annotation/modifier, superclass/interface, generic Mapper method, Controller return type, compact constructor, Java 테스트 annotation/method와 qualified type reference를 추가로 AST/source position에 연결했다. 다중 행 FQCN 우회 fixture를 추가했고, 첫 실프로젝트 회귀에서 stream 호출 체인의 중첩 결과 타입을 FQCN으로 오인한 8건을 Red 테스트로 고정해 제거했다. 최신 JAR을 Maven Local에 다시 게시한 뒤 `tool-test-lab`에서 기준선인 source 43건과 ArchUnit 3건의 동일한 정책 위반을 재확인했다.

## 1. 결론

현재 프로젝트는 **정해진 Java 21 + Spring Boot + JPA 패키지/코딩 규약을 강제하는 컨벤션 게이트**로는 강한 편이다. 특히 다음 항목은 실제 실패 조건으로 잘 연결되어 있다.

- Domain/Application/Adapter의 금지 의존 방향
- Domain과 JPA Entity의 분리
- JPA 연관관계 금지와 식별자 컬럼 규칙
- Application Service의 트랜잭션 위치, 읽기 전용 여부, 알려진 외부 부수 효과 호출
- Controller, Port, DTO, Service의 패키지와 일부 의존 규칙
- 테스트 태스크·정적 분석·커버리지 설정을 끄는 우회 방지

그러나 이 도구가 **Clean Architecture, Hexagonal Architecture, DDD, 객체 지향 설계가 올바르다고 보증한다**고 표현하면 범위를 과장하게 된다. 현재 구현이 강하게 확인하는 것은 주로 패키지, import, 어노테이션, 이름, 메서드 존재 여부와 같은 정적 형태다. Aggregate의 실제 일관성 경계, 도메인 행위의 응집도, 불변식의 소유 주체, Bounded Context 간 모델 번역, 캡슐화와 다형성 같은 의미적 설계는 확인하지 못한다.

따라서 최종 평가는 다음과 같다.

| 평가 영역 | 현재 수준 | 판단 |
|---|---|---|
| Clean Architecture 의존성 규칙 | 강 | 금지 방향을 source와 ArchUnit으로 이중 확인하고 Application 의존성을 allowlist로 제한한다. |
| Hexagonal Ports & Adapters | 중상~강 | Inbound/Outbound 방향뿐 아니라 Controller와 Application Service의 허용 collaborator도 긍정 규칙으로 확인한다. 실제 런타임 호출 의미 전체를 증명하지는 못한다. |
| Tactical DDD | 중상 | VO, 식별자, First-class Collection, Domain/JPA 분리와 Repository signature 기반 Aggregate Root를 검사한다. 실제 불변식의 의미는 수동 리뷰 대상이다. |
| Strategic DDD | 약 | Context Map, Published Language, Anti-corruption Layer, 팀/모듈 경계와 같은 전략 설계 검증은 없다. |
| 객체 지향 설계 | 중 | 불변성·일부 SRP 휴리스틱과 별도 `MUST-REVIEW` 체크리스트가 있다. 캡슐화, 응집도, Tell-Don't-Ask, 다형성, LSP/ISP/OCP는 자동으로 증명하지 않는다. |
| 테스트·품질 게이트 | 강 | 높은 커버리지 기준, 우회 방지, commit/staged/working/untracked 변경 커버리지, Validator/TestKit 회귀 테스트를 갖춘다. |
| `AGENTS.md` 프롬프트 품질 | 강 | 적용 범위, 우선순위, `MUST-AUTO`/`MUST-REVIEW`/`DEFAULT`, 자동 검증 한계를 명시하고 기존 모순을 정리했다. 상세 규칙의 길이와 일부 반복은 남는다. |

핵심적으로 이 프로젝트의 올바른 포지셔닝은 **“아키텍처 적합성의 필요조건을 자동 검사하는 도구”**다. 충분조건이나 설계 품질 보증 도구는 아니다.

### 1.1 후속 구현 상태

| 리뷰 개선 항목 | 현재 상태 | 근거 |
|---|---|---|
| Application dependency allowlist | 완료 | JDK, 같은 Context Domain/Port/Application 예외와 명시 annotation 외 import/FQCN을 거부하고 ArchUnit 기술 의존 규칙을 병행한다. |
| Controller/Application Service 긍정 collaborator 규칙 | 완료 | Controller는 `*UseCase`, Service는 `*Port` 또는 같은 Context Domain `*Service` 필드만 허용한다. |
| Bounded Context와 Aggregate 구분 | 완료 | 전역 `AGENTS.md`에서 Context를 Bounded Context로 고정했다. |
| Aggregate Root와 Repository 구조 연결 | 완료(시그니처 추론 범위) | Repository mutation 파라미터/반환 타입에서 Root가 정확히 하나인지 확인하고 같은 Context Root 직접 참조와 다중 Root Repository를 거부한다. |
| parser/root fail-closed | 부분 완료(주요 구조 완료) | base package와 Java syntax 실패를 거부한다. Java package/import/type/superclass/interface/field/record component/type-use annotation/constructor/method/transaction, compact constructor, Entity/Table/JPA field 판정, Java 테스트 method와 qualified type reference 탐색은 JDK AST/source position을 사용한다. 원본 test body도 `SourcePositions`로 보존한다. 호출 표현식과 SQL 검사는 순차 이전 대상이다. |
| 누적 migration/외부 reference | 완료(정적 SQL 범위) | migration 누적 텍스트와 external-reference metadata를 검증한다. 실제 DB metadata/Testcontainers는 후속이다. |
| 로컬 변경 커버리지 | 완료(Java 대상 프로젝트) | committed/staged/working/untracked 변경과 JaCoCo mapping 누락을 실패 처리한다. |
| Validator/TestKit 테스트 | 완료 | 직접 Validator 경계 fixture와 실제 Gradle TestKit 성공/실패 빌드를 포함한다. |
| 플러그인 저장소 자체 품질 게이트 | 완료 | `check -PchangedCoverageBaseRef=origin/main --rerun-tasks`에서 CodeNarc 무위반, 157개 테스트 무실패/skip 없음, JaCoCo line 89.79%(2559/2850), branch 80.38%(2540/3160)로 전체 line 85%/branch 80% 게이트를 통과했다. |
| 의미적 DDD/OOP 검토 | 수동 리뷰 | Context Map, 실제 불변식, 응집도, 다형성은 `MUST-REVIEW` 체크리스트로 유지한다. |
| mutation/실제 DB 검증 | 후속 | 플러그인 자체 PIT와 Testcontainers schema 검증은 아직 연결하지 않았다. |

### 1.2 최종 회귀 검증

- build-convention: `./gradlew check -PchangedCoverageBaseRef=origin/main --rerun-tasks` 성공
- 테스트 결과: 16개 test suite, 157개 테스트, failure 0, skipped 0
- Maven Local 검증 artifact: build JAR과 Maven Local JAR의 SHA-256이 모두 `2b80cefd9a1a639a5565a388a8440d569f19955531f9f87144632ceb1bbcda4a`
- `tool-test-lab` source gate: `validateArchitectureConventions`가 의도된 정책 위반 43건을 검출해 실패
- `tool-test-lab` 전체 gate: 테스트, Checkstyle, PMD, SpotBugs, JaCoCo 실행 후 ArchUnit이 Application의 Spring Data `Page` bytecode 의존 3건을 검출해 실패
- `tool-test-lab` source와 Git working tree는 변경하지 않았다.

## 2. 리뷰 범위와 검증 근거

- 리뷰 일자: 2026-07-11
- 후속 최종 검증 일자: 2026-07-12
- 저장소 기준 커밋: `4d164d6`
- 검토·수정한 프롬프트: `~/.codex/AGENTS.md`, 560줄
- 프롬프트 SHA-256: `b510452e7a75fdd772f5dc483e193aedefee2d8c3e036854652607f77925c297`
- 주요 검토 대상:
  - `LintConventionPlugin.groovy`
  - 모든 `validator`와 `SourceInspector.groovy`
  - Checkstyle, PMD, SpotBugs 내장 설정
  - 16개 테스트 클래스와 생성된 JUnit/JaCoCo 결과
  - `README.md`, `build.gradle`
- baseline 실행 명령: `./gradlew check` (66개 테스트 등록 기준 성공)
- 후속 최종 실행 명령: `./gradlew check -PchangedCoverageBaseRef=origin/main --rerun-tasks`
- 후속 최종 실행 결과: 성공, 157개 테스트 기준 failure/skip 없음

baseline 당시 이 저장소 자체의 `check` 출력은 7개 actionable task와 관련 lifecycle/no-source task만 보여 주었고, 핵심 검증은 `test`와 `validatePlugins`였다. 대상 프로젝트에 제공하는 architecture gate를 플러그인 저장소가 직접 검증하지 않았고 동등한 self-check 설정도 없었다. 후속 구현에서는 CodeNarc와 JaCoCo line/branch gate를 `check`에 연결했으며 최종 결과는 1.2절에 기록했다.

## 3. 잘 설계된 부분

### 3.1 의존성 방향을 source와 bytecode에서 이중 확인한다

`HexagonalArchitectureValidator`는 import 기준으로 다음 방향을 차단한다.

- Domain -> Application/Adapter
- Application -> Adapter
- Adapter In -> Adapter Out
- Adapter Out -> Adapter In

`ArchUnitArchitectureValidator:39-84`는 컴파일된 클래스 의존성으로 같은 핵심 규칙을 한 번 더 확인하고, Domain의 Spring/JPA/QueryDSL 의존과 Controller의 Service/Outbound Port 의존도 막는다. source 정규식을 bytecode 검증으로 일부 보완한다는 점이 좋다.

### 3.2 아키텍처 규칙을 `check`의 실제 실패 조건으로 연결한다

`LintConventionPlugin:319-356`, `444-460`은 정적 분석, ArchUnit, 커버리지, 커스텀 Validator를 `check`에 연결한다. 단순 권고 문서에 머무르지 않고 빌드 게이트로 만든 구조다.

또한 `LintConventionPlugin:465-640` 이후는 다음 우회를 탐지한다.

- 필수 태스크의 `-x`, `enabled = false`, 실패 무시, 거짓 `onlyIf`
- 테스트 filter/exclude와 JaCoCo 비활성화
- Checkstyle/PMD/SpotBugs 설정 파일 교체
- source/classDirectories/executionData 축소
- 커버리지·mutation 기준 완화
- 패키지 segment 변경과 예외 목록 사용

일반적인 “검증은 존재하지만 쉽게 꺼지는” 문제를 진지하게 다룬 점은 이 프로젝트의 가장 강한 부분이다.

### 3.3 Domain과 영속성 모델의 분리가 구체적이다

`ClaudeConventionValidator:890-1219`는 다음을 폭넓게 확인한다.

- Domain의 Spring/JPA/Lombok 어노테이션 금지
- Domain의 기술 id와 raw scalar/collection 일부 금지
- Domain record의 compact constructor, 식별자 factory, Entity 동등성 메서드 존재
- JPA Entity의 위치, protected 기본 생성자, 기술 id 비노출
- Entity 객체 참조와 JPA relation annotation 금지
- Domain 식별자와 Aggregate 참조를 문자열 컬럼으로 보관

이 규칙은 Domain Model과 Persistence Model을 분리하고 Aggregate 간 Lazy Loading 탐색을 막는 데 실질적인 효과가 있다.

### 3.4 트랜잭션 경계 규칙이 비교적 구체적이다

`TransactionBoundaryConventionValidator:54-107`은 Application Service에 대해 다음을 확인한다.

- 메서드 단위 `@Transactional`
- Query의 `readOnly = true`
- Command의 read-only 금지
- read-only 메서드의 Repository 변경 호출 금지
- self-invocation
- 알려진 외부 부수 효과 호출
- 둘 이상의 Repository 변경 호출
- 금지된 propagation과 `TransactionTemplate`

메서드명뿐 아니라 Command/Query 파라미터와 UseCase 이름을 함께 사용하는 점도 단순 이름 검사보다는 낫다.

### 3.5 프롬프트는 구현할 때 필요한 구체성이 높다

`~/.codex/AGENTS.md`는 패키지 트리, 타입 이름, 예외 위치, transaction 규칙, 테스트 설계표, 금지 예시를 제공한다. Agent가 “클린하게 작성해라” 같은 추상 지시를 임의 해석할 여지를 줄이고, 완료 보고에 Red/Green 명령과 남은 검증 공백까지 요구한다. Web error와 인증 규칙을 Domain/Application로부터 분리한 것도 일관성이 좋다.

## 4. 우선순위 높은 문제

이 절은 baseline에서 발견한 문제와 당시 근거를 보존한다. 각 항목의 후속 해결 여부는 1.1절과 해당 항목의 “후속 구현” 문단을 기준으로 판단한다.

### 높음 1. Application의 허용 의존성이 제한되지 않는다

프롬프트는 `AGENTS.md:73-77`에서 Application이 Domain과 자신이 소유한 Port에만 의존하고, Application Service가 Outbound Port만 호출한다고 선언한다.

하지만 실제 검증은 다음과 같다.

- `HexagonalArchitectureValidator:30-33`: Application의 Adapter import만 금지
- `ArchUnitArchitectureValidator:57-62`: Application의 Adapter bytecode 의존만 금지
- `ClaudeConventionValidator:747-749`: Spring Security 타입만 별도 금지
- `ClaudeConventionValidator:405-407`: 알려진 기술 **예외 타입** 노출만 금지

따라서 Application Service가 `JdbcTemplate`, `EntityManager`, Spring Data Repository, `RestTemplate`, SDK client, 다른 Context의 Application Service를 직접 의존해도 패키지가 Adapter가 아니면 핵심 의존성 검사를 통과할 수 있다. Transaction Validator가 일부 필드명과 타입의 호출을 잡더라도 기술 의존 자체를 금지하는 규칙은 아니다.

개선안:

1. Application에 대한 금지 목록이 아니라 허용 목록을 정의한다.
2. 허용 범위를 같은 Context의 Domain, `application.port.in/out`, JDK와 명시한 `@Service`/`@Transactional`로 제한한다.
3. Outbound Port의 signature에도 Spring/DB/HTTP/SDK DTO와 pagination 타입이 노출되지 않도록 bytecode 규칙을 추가한다.
4. Context 간 Application 의존은 Published API 또는 명시적 integration port를 통하도록 별도 규칙을 둔다.

이 항목은 Clean Architecture의 핵심 Dependency Rule과 직접 관련되므로 최우선이다.

### 높음 2. Aggregate 경계를 식별할 수 없어 핵심 DDD 규칙을 신뢰하기 어렵다

프롬프트의 `AGENTS.md:59`는 `{context}`를 “Bounded Context 또는 Aggregate 단위”라고 정의한다. 둘은 같은 개념이 아니며, 이 모호성 때문에 Validator가 Aggregate를 안정적으로 식별할 수 없다.

`ClaudeConventionValidator:1047-1081`의 cross-context 참조 검사는 다음 조건을 모두 만족해야만 직접 객체 참조로 판정한다.

- 다른 Context의 `domain.model` 타입을 import한다.
- import된 타입의 단순 이름이 Context 이름을 대문자화한 값과 같다.
- 그 타입이 필드 또는 record component에 나타난다.

예를 들어 `sales.domain.model.Order`를 다른 Context가 직접 참조하면 `Order != Sales`이므로 탐지하지 못한다. 같은 Bounded Context 안에 여러 Aggregate가 있을 때 Aggregate 간 직접 객체 참조도 탐지하지 못한다.

`TransactionBoundaryConventionValidator:188-208` 역시 “두 Repository 필드에서 mutation 메서드를 호출했는가”를 볼 뿐 Aggregate identity를 알지 못한다. 하나의 Repository가 여러 Aggregate를 저장하면 놓치고, 같은 Aggregate를 위한 두 Port는 잘못 차단할 수 있다.

개선안:

1. 최상위 `{context}`는 항상 Bounded Context로 정의한다.
2. Aggregate Root를 식별할 명시적 메타데이터를 도입한다. 예: 도메인 소유 marker interface/annotation 또는 `domain.model.{aggregate}` 패키지 규칙.
3. Repository Port 하나가 어떤 Aggregate Root를 관리하는지 구조적으로 연결한다.
4. 같은 Context와 다른 Context 모두에서 Aggregate Root 객체 직접 참조를 금지하고 식별자 VO 참조만 허용한다.
5. 전략적 DDD는 자동 검증 대상이 아니라 리뷰 체크리스트로 별도 관리한다.

### 높음 3. 정규식 parser와 fail-open 동작이 false confidence를 만든다

baseline에서는 대부분의 상세 규칙이 Java AST가 아니라 정규식으로 구현되어 있었다. 당시 대표적인 근거는 다음과 같다.

- `SourceInspector:24-35`: package/import 문장에 세미콜론이 있어야 파싱
- `TransactionBoundaryConventionValidator:245-300`: public method와 annotation을 정규식으로 추출
- `ClaudeConventionValidator:1264-1317`: 필드와 record component를 정규식으로 추출
- `ClaudeConventionValidator:2065-2069`: 최상위 타입을 파싱하지 못하면 해당 파일 검사를 종료

이 방식은 multiline syntax, 중첩 generic/annotation, fully-qualified type, 동일 파일의 여러 타입, 새 Java 문법에서 누락될 가능성이 높다. 특히 `SourceInspector`는 Groovy 파일도 수집하지만 일반적인 세미콜론 없는 Groovy package/import를 파싱하지 못한다.

더 큰 문제는 `PackageTopologyConventionValidator:79-84`다. 이름이 `Application`으로 끝나는 bootstrap class를 하나도 찾지 못하면 root package 집합이 비고, 모든 파일의 topology 검사가 위반 없이 종료된다. 라이브러리 모듈이나 bootstrap이 없는 MSA 하위 모듈에서 전체 패키지 규칙이 조용히 비활성화될 수 있다.

개선안:

1. JavaParser, Spoon, Eclipse JDT 같은 AST parser로 source 규칙을 옮긴다.
2. 의존성은 가능한 한 ArchUnit bytecode 규칙으로 옮긴다.
3. root package를 bootstrap 이름에서 추론하지 말고 extension에 필수 값으로 받거나 source set의 공통 package로 결정한다.
4. 파싱 실패, root 미결정, class directory 부재는 성공이 아니라 명시적 실패로 처리한다.
5. fully-qualified reference, multiline annotation, nested generic, text block, record compact constructor 변형을 adversarial fixture로 테스트한다.

후속 구현에서는 이 항목의 핵심 fail-open을 다음처럼 해소했다.

- javac batch parse 실패와 base package 미결정을 fail-closed로 처리한다.
- Java package/import/top-level type/field/record component/type-use annotation/type annotation/constructor/method를 JDK AST 모델에서 읽는다.
- Package topology, layer import, Entity/Table naming, Domain static factory, Transactional visibility/readOnly/body 경계를 AST에 연결했다.
- fully-qualified multiline import/annotation/type reference, annotation 내부 괄호, text block과 brace, record component 중복 모델을 adversarial Red/Green fixture로 검증했다.
- 실제 Java 식별자 구간만 qualified type으로 수집하고, stream 호출 체인의 `new Result.Item(...)`을 FQCN으로 오인한 실프로젝트 오탐을 별도 Red/Green fixture로 방지했다.
- 세미콜론 없는 Groovy package/import는 별도 source parsing으로 지원한다.

남은 정규식은 주로 호출 표현식과 메서드 body의 정책 패턴, SQL 문법이다. 따라서 parser 개선은 상당 부분 완료됐지만 “모든 Java 의미를 AST로 증명한다”는 수준은 아니다.

### 높음 4. Migration 규칙이 프롬프트와 충돌하고 증분 migration을 고려하지 않는다

프롬프트 `AGENTS.md:344`는 다른 서비스나 다른 데이터베이스의 식별자에는 외래키를 만들지 말라고 한다. 그러나 `MigrationConventionValidator:64-71`은 unique가 아닌 모든 `*_id` 식별자 컬럼에 index와 foreign key를 요구한다. 내부 DB 참조와 외부 서비스 식별자를 구분할 정보가 없기 때문이다.

또한 `MigrationConventionValidator:9-15`는 SQL 파일을 하나씩 독립적으로 검증하고, index/FK 검색도 같은 파일에서 수행한다. V1에서 테이블을 만들고 V2에서 index/FK를 추가하는 정상적인 증분 migration은 V1 파일 위반으로 판정될 수 있다.

추가로 정규식은 dialect, quoted identifier, `ALTER TABLE`, 복합 key와 여러 문장 변형을 안정적으로 모델링하기 어렵다.

개선안:

1. migration 파일을 버전 순서대로 누적 적용한 최종 schema 기준으로 검증한다.
2. 가능하면 Testcontainers와 실제 DB metadata로 unique/index/FK를 검증한다.
3. 외부 Context/서비스 식별자 컬럼을 명시하는 schema metadata 또는 migration DSL을 둔다.
4. 정적 SQL 검사는 명백한 기술 id 참조 같은 안전한 규칙으로 제한한다.

### 높음 5. 변경 코드 커버리지가 로컬 변경을 놓치고 여러 경로에서 통과한다

`ChangedCodeCoverageValidator:79-91`은 `${baseRef}...HEAD`와 `src/main/java`만 diff한다. 따라서 `check`를 실행하는 시점의 working tree, staged 변경, untracked 신규 Java 파일은 HEAD에 commit되지 않았다면 변경 커버리지 대상이 아니다. Agent가 구현 후 commit 전에 `./gradlew check`를 실행하는 일반적인 흐름과 맞지 않는다.

그 밖의 fail-open 경로도 있다.

- `LintConventionPlugin:215-221`: base ref를 찾지 못하면 log만 남기고 skip
- `ChangedCodeCoverageValidator:45-47`: executable changed line이 0이면 성공
- `ChangedCodeCoverageValidator:186-194`: JaCoCo report에서 파일이나 line을 찾지 못하면 무시

개선안:

1. CI에서는 base ref를 필수 입력으로 만들고 없으면 실패한다.
2. 로컬 모드는 base-to-HEAD, staged, unstaged, untracked 파일을 합쳐 계산한다.
3. 변경 Java 파일이 JaCoCo XML에 없으면 무시하지 말고 실패한다.
4. “CI 변경 커버리지”와 “로컬 미커밋 변경 커버리지”를 별도 태스크로 분리해 의미를 명확히 한다.

### 높음 6. 플러그인 저장소 자체의 검증과 핵심 Validator 테스트가 부족하다

현재 생성된 테스트 결과는 총 66개다.

- `ClaudeConventionValidatorTest`: 41개
- `LintConventionPluginTest`: 22개
- `HexagonalArchitectureValidatorTest`: 3개

그러나 다음 Validator에는 직접적인 기능 테스트 클래스가 없다.

- `ArchUnitArchitectureValidator`
- `ChangedCodeCoverageValidator`
- `EntityNamingConventionValidator`
- `DomainStaticFactoryValidator`
- `MigrationConventionValidator`

`PackageTopologyConventionValidator`와 `TransactionBoundaryConventionValidator`는 Claude Validator 테스트를 통해 일부 간접 검증된다. 반면 실제 Gradle task graph에서 sample Java 프로젝트에 플러그인을 적용하고 compile/test/check 전체를 실행하는 TestKit 기반 E2E 검증은 없다. `LintConventionPluginTest`는 `ProjectBuilder`에서 `check` action을 직접 실행해 주로 우회 방지만 확인한다.

프로젝트 테스트는 모두 `src/test/groovy`에 있어 `ClaudeConventionValidator:459-472`의 Java 테스트 규칙 대상에서도 제외된다. 즉 플러그인이 강제하는 한국어 `@DisplayName`, given/when/then, assertion 규칙을 플러그인 자체 테스트에는 dogfooding하지 않는다.

개선안:

1. 각 Validator에 정상, 위반, 경계, parser 우회 fixture를 추가한다.
2. Gradle TestKit으로 실제 Java sample project의 `check` 성공/실패를 검증한다.
3. 플러그인 자체에 PMD/SpotBugs/JaCoCo 또는 동등한 Groovy 품질 게이트를 적용한다.
4. 핵심 parser/Validator에 mutation test를 적용한다.
5. 자체 테스트 언어가 Groovy인 이유와 Java 테스트 컨벤션 비적용 범위를 문서화하거나 언어 중립 규칙으로 확장한다.

후속 구현에서는 Validator별 직접 경계 테스트와 Gradle TestKit E2E를 추가하고, CodeNarc와 JaCoCo 전체 line 85%/branch 80%를 self-gate로 연결했다. 최신 등록 결과는 16개 suite, 157개 테스트, failure/skip 0이다.

## 5. 프롬프트와 구현의 주요 불일치

| 프롬프트의 약속 | 실제 검증 | 판단 |
|---|---|---|
| Entity record의 `equals`/`hashCode`는 식별자만 사용 | `ClaudeConventionValidator:1997-2000`은 두 메서드 존재만 확인 | 잘못된 구현도 통과한다. |
| 식별자 `generate()`는 하이픈 없는 UUID 문자열 생성 | `1574-1576`은 이름과 반환 타입만 확인 | 상수, 잘못된 길이, 하이픈 UUID도 통과한다. |
| Domain Event 이름은 과거형/과거분사 | `698-707`은 `Event` suffix와 패키지만 확인 | 과거형 여부는 확인하지 않는다. |
| First-class Collection은 null/중복/크기/순서/불변 변경을 보장 | `1035-1043`은 `copyOf`와 null 요소 흔적만 확인 | 중복, 크기, 순서, 새 인스턴스 반환은 미검증이다. |
| JPA Entity 이름은 `{Domain}Entity` | `validateJpaEntity`는 `@Entity` 타입의 `Entity` suffix를 요구하지 않음 | 다른 이름의 `@Entity`가 통과한다. |
| Mapper는 방향 이름을 사용하고 조회·판단·시간·UUID·이벤트·reflection을 하지 않음 | `1221-1238`은 final/private constructor/non-bean만 확인 | 책임과 메서드 내용은 미검증이다. |
| JPA Entity에는 비즈니스 규칙이 없음 | relation, 생성자, 필드 형태는 확인하지만 메서드의 비즈니스 분기는 미검증 | anemic persistence model 원칙을 보증하지 못한다. |
| 공개 API는 `@PublicApi`, 인증 interceptor가 metadata 확인 | `/api/` exclude와 resolver의 `return null`만 탐지 | annotation 누락과 interceptor 동작은 미검증이다. |
| MSA 외부 `/api/**`, 내부 `/internal/**`, ingress는 내부 API 비노출 | package의 `external/internal`만 확인 | path 및 배포 설정은 미검증이다. |
| 사용자 노출 메시지만 한국어 | `1390-1402`는 모든 exception/super 문자열 literal을 한국어로 강제 | 내부 기술 메시지까지 과도하게 잡지만 message catalog 값의 한국어는 확인하지 않는다. |
| Adapter line/branch 80%/70% | coverage rule은 `adapter.out`만 별도 적용 (`LintConventionPlugin:1182-1186`) | `adapter.in`은 Adapter 계층 기준으로 별도 측정되지 않는다. |
| 설정 flag로 세부 검증 on/off 표현 | `enforceTestConventions`, `enforceApiDtoLayerSeparation`, `enforceDomainRawScalarProhibition`, `enforceStrictClaudeConventions`는 우회 탐지 외 실제 Validator 분기에 사용되지 않음 | 설정 API가 실제 동작보다 많은 선택지를 암시한다. |

이 표의 일부는 자동화하기 어려운 의미 규칙이다. 그런 규칙은 억지 정규식으로 “검증됨”처럼 보이게 하기보다 `자동 검증`, `휴리스틱 경고`, `사람이 리뷰`로 등급을 나누는 편이 정확하다.

## 6. Clean Architecture, DDD, Hexagonal, 객체 지향 관점별 상세 평가

### 6.1 Clean Architecture

좋은 점은 Dependency Rule을 패키지와 bytecode 수준에서 명시적으로 구현한 것이다. Domain의 프레임워크 독립성도 강하게 다룬다.

다만 `@Service`와 `@Transactional`을 Application에 직접 붙이고 Context별 composition configuration을 금지하는 것은 순수 Clean Architecture의 필연적 규칙이 아니라 **Spring 실용주의를 선택한 팀 정책**이다. 외부 composition root에서 순수 Application 객체를 조립하는 방식도 정당한 Clean Architecture 구현이다. 현재 선택을 유지할 수는 있지만 “유일하게 올바른 구조”가 아니라 의도적인 trade-off로 문서화해야 한다.

또한 Application dependency whitelist가 없으므로 가장 중요한 내부 계층 순수성이 완전히 보장되지 않는다.

### 6.2 Hexagonal Architecture

Inbound/Outbound Port, Web/Persistence Adapter, 의존 역전 방향은 잘 드러난다. Controller -> Service 구현체와 Adapter In -> Adapter Out을 차단한 것도 적절하다.

그러나 “Controller는 Inbound Port만 호출”한다는 긍정 규칙은 완전히 증명하지 않는다. `ArchUnitArchitectureValidator:75-84`는 Controller가 Adapter Out, Application Service, Outbound Port에 의존하지 않는지만 확인한다. 다른 Domain 객체나 임의 외부 collaborator 의존은 이 규칙의 메시지와 달리 허용될 수 있다. Application Service가 실제로 Outbound Port만 사용하는지도 같은 문제가 있다.

Hexagonal 검증을 강화하려면 금지 목록 외에 component별 허용 의존성과 method call target 규칙이 필요하다.

### 6.3 DDD

VO, Entity identity, Aggregate 간 id 참조, First-class Collection, Domain Event, Repository Port 같은 tactical pattern은 매우 자세하다. 다만 다음은 DDD 자체가 아니라 프로젝트 선택이다.

- 모든 Entity/Aggregate를 record 우선으로 작성
- 모든 식별자를 32자리 무하이픈 UUID 문자열로 고정
- 테이블 복수형, Entity 단수형 영어 휴리스틱
- 모든 컬렉션을 반드시 별도 record로 감싸기

이 선택은 팀 컨벤션으로는 가능하지만 DDD 준수 여부와 동일시하면 안 된다. 특히 Entity와 Aggregate는 identity와 lifecycle이 핵심이므로 VO/Domain Event에는 record를 우선하되 Entity/Aggregate는 행위와 상태 전이에 맞춰 `final class` 또는 record를 선택하는 편이 자연스럽다.

Strategic DDD 측면에서는 다음 검토가 별도로 필요하다.

- Bounded Context 경계와 Context Map
- Shared Kernel 사용 여부와 소유권
- Upstream/Downstream, Conformist, Anti-corruption Layer
- Context 간 command/event/API의 Published Language
- Ubiquitous Language와 타입 이름의 일치
- Aggregate가 보호하는 실제 불변식과 transaction boundary

이 항목은 일반적인 static validator가 완전히 판단하기 어렵다. 설계 기록과 리뷰 체크리스트가 필요하다.

### 6.4 객체 지향

프롬프트는 SRP와 불변성을 강조하지만 실제 SRP 검사는 `Manager`, `Helper`, `Util(s)` 이름과 Service의 UseCase 수, Adapter의 Port 수를 세는 정도다 (`ClaudeConventionValidator:261-292`). 이 휴리스틱은 유용한 냄새 탐지지만 책임의 응집도를 판정하지는 못한다.

PMD 설정은 일반/Domain 모두에서 `LawOfDemeter`, `CyclomaticComplexity`, `CognitiveComplexity`, `CouplingBetweenObjects`, `ExcessiveImports` 등을 제외한다. 따라서 객체 간 과도한 탐색, 높은 결합도와 복잡도를 OOP 관점에서 적극 검증하지 않는다.

전역적인 `else` 금지는 guard clause를 장려하는 팀 스타일이지 OOP 원칙은 아니다. 모든 `else`를 오류로 만들면 대칭적인 두 분기, exhaustive branching, 상태 전이 표현에서 오히려 가독성이 나빠질 수 있다. “중첩을 줄일 수 있으면 guard clause를 우선한다”는 리뷰 지침으로 완화하거나 최대 nesting/complexity 규칙으로 목적을 직접 측정하는 편이 낫다.

추가로 수동 리뷰해야 할 OOP 항목은 다음과 같다.

- Domain 모델이 getter/setter 중심의 anemic model인지
- 행위와 불변식이 데이터를 소유한 객체에 함께 있는지
- 외부에서 상태를 조립하지 않고 intention-revealing method를 사용하는지
- 상속보다 합성을 적절히 사용하는지
- interface가 소비자별로 작고 응집되어 있는지
- mutable collection이나 내부 표현이 새어 나오는지
- 구현 상세를 묻는 연속 getter 호출이 있는지

## 7. `~/.codex/AGENTS.md` 프롬프트 리뷰

7.2~7.6의 문제 설명은 baseline 프롬프트를 기준으로 한다. 현재 수정본은 적용 범위와 우선순위, `MUST-AUTO`/`MUST-REVIEW`/`DEFAULT`, Codex 명칭, Context/Aggregate 구분, UUID 테스트 정책을 반영했으며 1.1절의 상태가 최종 판단이다.

### 7.1 잘 작성된 점

- 계층별 책임, 패키지, 이름, 금지 의존을 구분해 탐색이 쉽다.
- 예외와 HTTP 변환 책임을 분리해 내부 계층의 기술 독립성을 유지한다.
- Aggregate를 객체 그래프가 아닌 일관성 경계로 설명한다.
- 테스트 설계표가 정상/실패/경계/중복/상태/Port/Web을 포함한다.
- 검증 우회 금지와 완료 보고 조건이 구체적이다.
- “코드만으로 이유가 드러나지 않는 경우”의 짧은 주석 예외처럼 의도를 설명한다.

### 7.2 적용 범위가 전역 프롬프트와 맞지 않는다

파일은 `~/.codex/AGENTS.md`라는 전역 위치에 있지만 첫 제목은 `Global Claude Code Guidance`이고, 내용은 Java 21, Spring, JPA, Lombok, Spring MVC에 강하게 결합되어 있다. 다음 문제가 있다.

1. Codex에서 사용하는 파일인데 Claude 명칭이 남아 있다.
2. Java/Spring/JPA가 아닌 저장소에도 전역 규칙으로 보일 수 있다.
3. 첫 문장은 “신규 프로젝트와 큰 구조 변경”의 기본값이라고 하지만 이후 금지 규칙은 기존/소규모 변경에도 절대 규칙처럼 작성되어 있다.
4. 기존 프로젝트의 architecture, 사용자 요청, repository-local `AGENTS.md`와 충돌할 때 우선순위가 없다.

권장 방식은 전역 파일에는 언어 중립 작업 원칙과 적용 조건만 두고, Java/Spring/JPA 상세 규칙은 이 플러그인을 사용하는 저장소의 repo-local `AGENTS.md` 또는 별도 include 문서로 옮기는 것이다.

### 7.3 원칙, 팀 정책, 자동 검증 가능성을 구분해야 한다

현재 문서는 다음 세 종류를 같은 강도의 규칙으로 섞는다.

- 아키텍처 원칙: 내부 계층은 외부 계층을 모른다.
- 팀 구현 정책: Lombok `@RequiredArgsConstructor`, 32자리 UUID, 복수형 테이블.
- 스타일 선호: `else` 금지, 한국어 `@DisplayName`, given/when/then 주석.

각 항목에 다음 라벨을 붙이면 Agent와 사람 모두 기대 수준을 정확히 이해할 수 있다.

- `MUST-AUTO`: 빌드가 자동으로 증명하며 위반 시 실패
- `MUST-REVIEW`: 의미적 규칙이라 사람이 설계/코드를 리뷰
- `DEFAULT`: 신규 코드의 기본 선택이며 맥락상 더 나은 대안이 있으면 근거를 남기고 변경 가능
- `SCOPE`: Java/Spring/JPA 또는 MSA처럼 적용 전제 명시

### 7.4 내부 모순을 해소해야 한다

가장 명확한 모순은 UUID 생성이다.

- `AGENTS.md:132`, `157-158`: 식별자 VO가 `UUID.randomUUID()`로 직접 생성
- `AGENTS.md:453`: 시간, UUID 같은 비결정 값은 Port 또는 주입 가능한 정책으로 통제

둘 중 하나를 선택해야 한다. 테스트 가능성과 Application orchestration을 중시한다면 `IdGeneratorPort`가 값을 만들고 Domain factory가 `UserId`를 받도록 하는 편이 일관적이다. Domain의 편의 factory를 유지하려면 UUID는 구조적 동등성만 검증하고 정확한 생성값을 assertion하지 않는다는 테스트 정책을 명시해야 한다.

또한 `{context}`를 Bounded Context와 Aggregate 중 하나로 혼용하는 정의도 제거해야 한다. 최상위 Context는 Bounded Context, 그 내부에서 Aggregate 경계를 별도로 표현해야 한다.

### 7.5 중복과 길이를 줄여 drift를 방지해야 한다

본문의 상세 규칙 상당수가 마지막 `Prohibited Patterns`에서 다시 반복된다. 현재 수정본도 560줄로 참고 문서로는 상세하지만 Agent의 매 작업 지침으로는 길고, 같은 규칙을 두 군데 수정해야 하므로 drift 가능성이 높다.

권장 구조:

1. 적용 범위와 우선순위
2. 15~25개의 핵심 `MUST` 규칙
3. 자동 검증 명령과 완료 조건
4. 의미적 설계 리뷰 체크리스트
5. 상세 Java/Spring/JPA reference 문서 링크

`Prohibited Patterns`는 본문에서 이미 설명한 항목을 반복하지 말고, 자동 검증이 놓치기 쉬운 고위험 패턴만 남기는 편이 낫다.

### 7.6 명칭을 도구 중립적으로 맞춰야 한다

다음 명칭은 현재 파일과 목적에 맞춰 정리할 필요가 있다.

- `Global Claude Code Guidance` -> `Codex Java/Spring Architecture Guidance` 또는 repo-local `Architecture Guidance`
- `ClaudeConventionValidator` -> `ArchitectureConventionValidator` 또는 `AgentConventionValidator`
- `validateClaudeConventions` -> `validateArchitectureConventions`
- Gradle plugin 설명의 `CLAUDE.md conventions` -> `AGENTS.md architecture conventions`

이름을 바꾸면 특정 Agent 제품명보다 실제 검증 책임이 드러나고 문서와 코드의 추적성도 좋아진다.

## 8. 권장 프롬프트 개정 골격

아래는 전체 재작성본이 아니라 현재 문서 앞부분에 추가할 핵심 골격이다.

```markdown
# Java 21 / Spring Boot / JPA Architecture Guidance

## 적용 범위와 우선순위

- 이 문서는 Java 21 이상, Spring Boot, JPA를 사용하는 신규 프로젝트와 합의된 구조 개편에 적용한다.
- 기존 저장소에서는 사용자 요청과 repository-local AGENTS.md, 현재 architecture를 우선하고 최소 변경 원칙을 지킨다.
- 다른 언어, non-Spring 모듈, migration 중인 legacy 모듈에는 자동 적용하지 않는다.
- 팀 고유 구현 정책은 Clean Architecture/DDD의 보편 원칙과 구분한다.

## 검증 수준

- MUST-AUTO: `./gradlew check`가 증명하는 구조 규칙이다.
- MUST-REVIEW: Aggregate 경계, 불변식, 도메인 언어처럼 사람이 검토해야 한다.
- DEFAULT: record, 식별자 포맷, naming처럼 신규 코드의 기본 선택이다.

## 핵심 의존성 규칙 — MUST-AUTO

- Domain은 외부 계층과 framework에 의존하지 않는다.
- Application은 같은 Bounded Context의 Domain과 자신이 소유한 Port에만 의존한다.
- Adapter는 Port를 구현하며 내부 계층 구현체가 Adapter를 참조하지 않는다.

## DDD 설계 검토 — MUST-REVIEW

- 최상위 context는 Bounded Context다. Aggregate와 혼용하지 않는다.
- 각 Aggregate가 보호하는 불변식과 transaction boundary를 변경 전에 적는다.
- 다른 Aggregate는 같은 Context 안에서도 식별자 VO로만 참조한다.
- Context 간 모델을 공유할 때 Published Language, ACL 또는 Shared Kernel 선택 근거를 남긴다.
```

Domain 모델 기본값도 다음처럼 조정하는 편이 DDD 의미에 더 가깝다.

```markdown
- Value Object와 Domain Event는 record를 기본값으로 한다.
- Entity와 Aggregate Root는 identity, lifecycle, 상태 전이에 따라 record 또는 불변 final class를 선택한다.
- 식별자 생성 정책은 Application의 generator port에서 통제하고 Domain factory에는 생성된 식별자 VO를 전달한다.
- guard clause는 중첩을 줄일 때 우선한다. else 자체를 일률적으로 금지하지 않는다.
```

## 9. 개선 로드맵

### 1단계: 문서와 실제 보장 범위 정렬

1. 전역/저장소 적용 범위를 분리한다.
2. Claude/Codex/AGENTS 명칭을 통일한다.
3. UUID 생성 모순과 Context/Aggregate 정의를 수정한다.
4. 각 규칙에 `MUST-AUTO`, `MUST-REVIEW`, `DEFAULT`를 표시한다.
5. README의 “검증한다”를 자동 검증 범위에 맞게 표현한다.

### 2단계: 핵심 architecture gate 보강

1. Application dependency whitelist를 추가한다.
2. Controller와 Service의 허용 dependency/call target을 긍정 규칙으로 검증한다.
3. package root 미결정을 fail-closed로 바꾼다.
4. Aggregate Root를 명시적으로 식별하고 같은 Context 내 직접 참조도 차단한다.
5. 정규식 규칙을 AST/ArchUnit 기반으로 순차 이전한다.

### 3단계: 품질 게이트의 신뢰성 보강

1. 변경 커버리지에 working tree/staged/untracked 변경을 포함한다.
2. CI base ref 누락과 JaCoCo mapping 누락을 실패 처리한다.
3. migration을 누적 schema 또는 실제 DB metadata로 검증한다.
4. 모든 Validator의 직접 테스트와 TestKit E2E fixture를 추가한다.
5. 플러그인 저장소 자체에 lint/coverage/mutation을 적용한다.

### 4단계: 의미적 리뷰 체계 추가

자동화하기 어려운 다음 질문을 PR 템플릿 또는 ADR 체크리스트로 관리한다.

- Aggregate가 보호하는 불변식은 무엇인가?
- transaction 하나가 왜 이 Aggregate 경계와 일치하는가?
- 도메인 행위가 Application/Controller가 아니라 Domain에 위치하는가?
- 다른 Context의 모델을 그대로 수입하지 않고 번역하는가?
- Port가 소비자의 언어로 작고 응집되어 있는가?
- DTO/Mapper/Entity에 비즈니스 판단이 새어 나오지 않는가?

## 10. 최종 판정

이 프로젝트는 현재도 충분히 가치가 있다. 일반적인 lint plugin보다 architecture와 transaction 규칙이 훨씬 구체적이고, 검증 우회 방지까지 포함한다는 점은 분명한 장점이다.

후속 구현으로 높음 우선순위의 핵심 fail-open과 테스트 공백은 상당 부분 해소되었다. 현재 도구는 **특정 Java/Spring/JPA 구현 스타일의 정적 구조 적합성**을 강하게 검증하는 architecture fitness function으로 보는 것이 정확하다. 다만 남은 표현식/JPA/SQL 휴리스틱, 실제 DB metadata, mutation, Aggregate의 실제 불변식과 OOP 응집도는 여전히 자동으로 증명하지 않는다. 이 한계를 `MUST-REVIEW`와 후속 항목으로 유지해야 과도한 확신 없이 사용할 수 있다.
