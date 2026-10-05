# Codex Java/Spring Guidance

Java 21+, Spring Boot, JPA를 사용하고 `io.github.dochiri0916.build-convention`을 적용한 소스 모듈에 이 지침을 적용한다. 최신 사용자 요청과 대화에서 명시적으로 합의한 결정, 저장소의 `AGENTS.md`, 저장소의 기존 구조가 이 지침보다 우선한다. 기존 프로젝트의 작은 변경에서는 요청 범위 밖 구조 개편이나 기존 코드 일괄 마이그레이션을 하지 않는다.

기준 예시는 `tool-test-lab` 저장소이고, 검증을 통과하는 파일 단위 틀은 `docs/templates`(`com.example.demo`, Context `book`)에 있다. 이 지침에 없는 구조와 스타일은 검증에서 거부된다. 다음 경로는 build-convention 저장소의 루트에서 해석한다.

- `docs/ARCHITECTURE.md`: 패키지, 계층, Domain/Application/Adapter 책임
- `docs/ERROR_HANDLING.md`: Context별 sealed 예외, ExceptionHandler, ProblemDetail
- `docs/TESTING.md`: 테스트 작성 방식과 검증 명령

## 핵심 구조

```
Domain <- Application <- Adapter
```

- Domain은 Spring, JPA, Lombok, Application, Adapter를 모른다.
- Application은 같은 Context의 Domain과 자신이 소유한 Port, 그리고 공유 커널(`global.domain`, `global.application.exception`)만 사용한다.
- Application은 실용적 예외로 Spring의 stereotype·transaction annotation과 compile-time Lombok을 사용할 수 있다. 그 외 Jackson, Servlet, Kafka, Redis, JDBC/ORM 드라이버 같은 기술 타입은 Application과 Domain에서 사용할 수 없다.
- Controller는 Inbound `UseCase`만 호출하고, Application Service는 Outbound `Port` 또는 무상태 Domain Service만 주입받는다.
- `{context}`는 Bounded Context다. 다른 Context의 모델·Application 타입은 직접 import하지 않고 통합 Port와 Adapter(예: `cart.adapter.out.product.ProductCatalogAdapter`)로 번역한다.
- 묶음 Context(`catalog/category`, `catalog/product`)는 하나의 Context다. 같은 묶음의 모듈끼리는 Port와 Domain 모델을 직접 사용할 수 있다.
- `common` 패키지를 만들지 않는다.

## 패키지

```
com.example.project/
├── global/
│   ├── configuration/          # @Configuration, properties/ 에 @ConfigurationProperties
│   ├── domain/                 # 여러 Context가 쓰는 vo/(Money, Quantity), exception/, outbox/
│   ├── application/exception/  # 공통 Application 예외(RepositoryOperationException)
│   ├── exception/              # GlobalApiControllerAdvice, ProblemDetails
│   └── persistence/            # BaseEntity, RepositoryExceptionTranslator
└── {context}/ 또는 {context}/{module}/
    ├── domain/                 # Aggregate Root를 직접 두거나 model/, vo/ 에 둔다. event/, exception/, outbox/
    ├── application/{port/in/{usecase,command,query,result},port/out,exception,service}/
    └── adapter/{in/{web,bootstrap},out/{persistence,...}}/
```

- 입력 계약은 `port.in.usecase`·`command`·`query`·`result`로 분리한다. `port.in` 바로 아래에는 타입을 두지 않는다.
- `adapter/in/web`에는 Controller, `{Context}ExceptionHandler`, `request/`, `response/`, (필요하면) `Mapper`를 둔다. 웹 예외 처리기는 하위 exception 패키지 없이 웹 루트에 둔다.
- `global.web`은 쓰지 않는다. 공통 메시지 인프라는 `global.messaging`에 둔다.
- 도메인 이벤트는 `domain/event`의 `record`다. 이름은 과거형이고 `Event` 접미사를 붙이지 않으며(`BookRegistered`), compact constructor에서 null을 막는다. 발행은 Application이 Outbound Port(`{Context}EventPort`)로 한다.
- `global`은 어떤 `{context}` 패키지도 import하지 않는다.

### 웹 입력 계약

- Request·Response는 원시 값 중심의 record다. 외부 계약의 컬렉션은 모든 생성자에서 `List.copyOf`·`Set.copyOf`·`Map.copyOf`로 복사한다.
- 모든 Request 필드에 Jakarta 입력 제약을 선언한다. 문자열은 `@NotBlank`, 컬렉션은 `@NotEmpty`, 숫자는 범위 제약을 사용하고 각 제약의 `message`에 한국어 사유를 명시한다. Controller의 요청 본문은 `@Valid @RequestBody`로 받는다.
- Request의 단순 변환은 public 인스턴스 `toCommand()`·`toQuery()`, Response의 변환은 public static `from(final XxxResult result)`에 둔다. Request 변환에 분기·반복이 필요하면 웹 Mapper로 분리한다. 경로·인증 정보는 변환 메서드의 인자로 전달한다.
- 서비스 호출·업무 판단은 DTO 변환의 책임이 아니다. 대응하는 계약이 없는 요청·응답을 위해 빈 Command·Result를 추가하지 않는다.

## Domain

- Aggregate Root는 `final class`이고 상태 필드는 `private final` Value Object·enum·First-class Collection이다. 값을 받는 constructor는 `private`, 생성은 `create`, 영속 상태 복원은 `restore`를 쓴다.
- Value Object는 `final class`이고 private constructor에서 불변식을 검증하며 `of()`로 생성한다. `parse()`·`empty()`도 허용한다.
- 식별자는 UUID v7을 감싸는 `{Domain}Id` VO이며 생성자에서 version 7을 검증하고 `generate()`로 발급한다. 다른 Context를 가리키는 `*ReferenceId`는 `generate()`가 없다.
- 상수는 `private static final int MAX_LENGTH = 1_000;`처럼 둘 수 있다.
- 실패는 Context의 Domain 예외를 `throw new ...Exception()`으로 던진다. JDK 기본 예외(`IllegalArgumentException` 등)는 쓰지 않는다.
- 업무 예외는 같은 Context·계층의 abstract sealed 기반 예외를 상속하는 final 클래스다. 기반 생성자는 모두 protected이며 `permits`에 실제 존재하는 모든 final 직계 예외를 명시한다. 공통 VO 예외의 기반은 `GlobalDomainException`이다. 이전 예외 개수 제한·분할 허용 설정은 새 계층 규칙을 비활성화하지 않는다.
- 상태 변경은 식별자를 유지하는 새 Aggregate를 반환한다. 다른 Aggregate는 식별자 VO로만 참조한다.

### 도메인 값과 메시지 모델

- VO는 모든 구성 값으로 `equals`·`hashCode`를 구현하고 Entity는 식별자로 구현한다.
- 도메인 컬렉션은 컬렉션 하나만 인스턴스 필드로 갖는 일급 컬렉션이다. 생성자에서 `List.copyOf`·`Set.copyOf`·`Map.copyOf`로 복사하고 변경 가능한 내부 컬렉션을 노출하지 않는다.
- revision·sequence 메타데이터에는 원시 타입을 허용한다. 상태 enum에는 VO의 final class·private 생성자·팩터리 규칙을 적용하지 않는다.
- 도메인 이벤트는 루트의 상태로 보관하지 않고 `placedEvent()` 같은 생성 메서드로 제공한다. 이벤트에는 도메인 객체·VO를 담을 수 있다.
- 불변 Outbox 모델은 `global.domain.outbox`·`{context}.domain.outbox`에 두며 record를 허용한다. 영속성과 전송은 어댑터가 담당한다.
- `global.domain`에는 여러 BC에서 의미가 같은 VO·예외·Outbox만 둔다. 불변식의 완전성·소유권 규칙·루트 내부 변경·restore 업무 우회 여부는 코드 리뷰로 확인한다.
- 템플릿의 UUID v7 발급에는 `com.github.f4b6a3:uuid-creator:6.1.1` 의존성이 필요하다. 자체 식별자는 하이픈을 포함한 36자 UUID 문자열로 저장한다. 기존 DB에서는 시작 전에 컬럼과 제약을 마이그레이션한다.

## 공통 코드

- 클래스는 final로 선언한다. 명시적 상속 기반 클래스·JPA Entity·상속 기반 프록시가 필요한 Application/Adapter는 예외다.
- 모든 명시적 메서드·생성자 파라미터는 final이다. record compact constructor의 암시적 파라미터는 예외다.
- else 대신 early return을 사용하고 제어 분기 중첩은 한 단계까지만 허용한다. Adapter의 JPA Entity·DTO에는 프레임워크 스타일 예외를 적용한다.
- 한 줄에 점을 하나만 사용한다. builder·Stream·Optional 체이닝은 예외다. 접근자는 필드 이름을 쓰고 setter 이름을 쓰지 않는다.
- 이름의 의미·단일 책임·Tell Don't Ask·협력 객체 5개 초과 검토는 코드 리뷰 기준이다. 도메인 필드 수에는 상한이 없다.

## Application

- Command·Query·Result와 출력 포트의 컬렉션은 해당 계약 패키지의 불변 일급 컬렉션(class 또는 record)으로 감싼다. 원소 DTO는 원시 값 중심으로 유지한다.
- 내부 검색 조건은 `port.out`의 `{Domain}SearchCondition` record다. 서비스는 Query를 VO·enum으로 바꿔 전달한다.
- Result 변환은 Application 루트의 `{Domain}ResultMapper`에 두고 이벤트 변환은 `{Bc}EventMapper`에 둔다.
- Repository Port의 도메인 식별자·값에는 VO를 사용하고 page·size·sequence 메타데이터에는 원시 값을 허용한다.

- 각 Application Service는 `final`이고 정확히 하나의 Inbound UseCase를 구현한다. 메서드 하나인 UseCase와 Port에는 `@FunctionalInterface`를 붙인다.
- 트랜잭션은 public Application Service 메서드에 둔다. 조회와 로그인은 `@Transactional(readOnly = true)`를 사용한다.
- Repository Port는 `save`, `create`, `update`, `find...`, `exists...`, `delete...`를 쓴다. `upsert`는 쓰지 않는다.
- Command는 record compact constructor에서 입력을 검증하고 실패 시 Context의 `InvalidCommandException`을 던진다.
- 저장소 기술 예외는 `RepositoryOperationException`으로 바꿔 Port 밖으로 전달한다.

## Adapter와 오류

- Controller는 요청 파싱, Command 생성, UseCase 호출, Response 변환만 담당한다. JPA 엔티티와 Spring Web 어노테이션은 와일드카드 import(`jakarta.persistence.*`, `org.springframework.web.bind.annotation.*`)를 쓸 수 있고, 그 외에는 쓰지 않는다.
- JPA Entity는 `adapter.out.persistence`에만 두고 `BaseEntity`를 상속한다. 자동 생성 기술 키 `Long id`와 자체 UUID 컬럼 `publicId`/`public_id`(String, 36자, 필수, 유일)를 둔다. 부모·외부 참조는 `{target}Id`, 메시지·스냅샷 키는 목적별 이름을 유지한다.
- Mapper는 `final` 유틸리티 클래스다. 자식 행 키 UUID는 만들 수 있지만 시간 값은 만들지 않는다.
- 예외 응답은 Context별 `{Context}ExceptionHandler`(`@RestControllerAdvice`, `HIGHEST_PRECEDENCE`)가 sealed 예외를 `switch`로 나눠 `ProblemDetails.create(status, code, message)`로 만든다. 나머지는 `GlobalApiControllerAdvice`가 500으로 처리한다.
- Domain/Application/Outbound Port는 DB, HTTP, SDK, Spring 기술 예외를 노출하지 않는다.
- Persistence Adapter의 조회·변환은 `RepositoryExceptionTranslator.execute`로 감싼다. 일반 save/delete는 번역 경계를 나가기 전에 flush하고, 지정된 제약 이름만 업무 충돌로 변환하며 원인을 보존한다. 실패한 catch 블록에서 저장을 재시도하지 않는다.

### Querydsl

- Q 타입은 APT가 생성하며 Git에 포함하지 않는다. 검사는 프로젝트 아래 Java 파일의 Git index 스냅샷을 읽으므로 staging 후 작업 파일 수정·삭제로 우회할 수 없다. Git이 없는 소스 배포본에는 index 검사가 적용되지 않는다.
- Q 기본 인스턴스는 static import한다. 같은 Entity를 여러 번 조인할 때의 역할별 별칭은 코드 리뷰로 확인하며, `new QBookEntity("buyer")` 같은 별칭 생성은 허용한다.

- 동적 검색은 `adapter.out.persistence`의 구체 `@Component`인 `*QueryRepository`에서 실행한다. 가변 `JPAQuery`를 필드로 보관하거나 외부에 반환하지 않는다.
- `JPAQueryFactory`는 `global.configuration.QuerydslConfiguration`의 `@Bean`으로 제공하며 Spring이 주입한 `EntityManager`를 사용한다.
- 내부 투영은 같은 영속성 영역의 public `*Row` record와 `Projections.constructor`로 표현한다. `@QueryProjection`도 내부 Row에만 둔다.
- 다른 BC의 Entity·저장소를 직접 조회하지 않는다. `fetchResults`·`fetchCount`는 쓰지 않고 목록과 count를 같은 조건의 별도 쿼리로 구성한다. 페이징에는 유일한 키를 포함한 정렬을 두고 컬렉션 fetch join은 함께 쓰지 않는다.
- 페이징에는 `id`·`publicId` 또는 조회 Entity의 NOT NULL 단일 열 유일 제약에 해당하는 정렬 키를 포함한다. 지역 정렬 변수와 인자 없는 지역 헬퍼도 검사한다. 직접 offset 곱셈과 지역 변수·지역 헬퍼로 위임한 offset 곱셈은 첫 곱셈부터 long으로 계산해야 하며, 곱셈 후 캐스팅은 허용하지 않는다.
- `Response.from(Result)`의 내부 변환에서는 같은 BC의 Result DTO·일급 컬렉션을 참조할 수 있다. Response 필드나 공개 반환 타입으로 애플리케이션 모델을 노출하지 않는다.
- 페이지 범위·외부 헬퍼 offset 계산·복합 유일 키 전체 정렬·조회 조인에 따른 실제 유일성·목록/count의 동일 조건·N+1 방지는 DB 테스트와 코드 리뷰로 확인한다. 상세 적용 근거는 `docs/conventions/coverage.json`에서 확인한다.

## 품질

- 테스트는 한국어 `@DisplayName`과 `// given`, `// when`, `// then` 구조를 사용한다.
- 예외 테스트는 예외 타입과 메시지를 검증한다.
- 줄 길이는 130자 이하, 사용하지 않는 import는 남기지 않는다.
- 검증을 비활성화하거나 제외해 실패를 숨기지 않는다.
- Production, 테스트, build 설정, migration을 바꾸면 완료 전에 `./gradlew check`를 실행한다.
