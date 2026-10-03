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
│   ├── domain/                 # 여러 Context가 쓰는 값 객체(Money, Quantity), exception/
│   ├── application/exception/  # 공통 Application 예외(RepositoryOperationException)
│   ├── exception/              # GlobalApiControllerAdvice, ProblemDetails
│   └── persistence/            # BaseEntity, RepositoryExceptionTranslator
└── {context}/ 또는 {context}/{module}/
    ├── domain/                 # Aggregate Root를 직접 두거나 model/, vo/ 에 둔다. event/, exception/
    ├── application/{port/in,port/out,exception,service}/
    └── adapter/{in/{web,bootstrap},out/{persistence,...}}/
```

- `port/in`에는 UseCase를 바로 두고, Command와 Result는 같은 곳이나 `port/in/command`, `port/in/result`에 둔다. `usecase`, `query` 하위 패키지는 쓰지 않는다.
- `adapter/in/web`에는 Controller, `{Context}ExceptionHandler`, `request/`, `response/`, (필요하면) `exception/`을 둔다.
- `adapter.in.scheduler/messaging/event`, `global.web`은 쓰지 않는다.
- 도메인 이벤트는 `domain/event`의 `record`다. 이름은 과거형이고 `Event` 접미사를 붙이지 않으며(`BookRegistered`), compact constructor에서 null을 막는다. 발행은 Application이 Outbound Port(`{Context}EventPort`)로 한다.
- `global`은 어떤 `{context}` 패키지도 import하지 않는다.

## Domain

- Aggregate Root는 `final class`이고 상태 필드는 `private final` Value Object·enum·First-class Collection이다. 값을 받는 constructor는 `private`, 생성은 `create`, 영속 상태 복원은 `restore`를 쓴다.
- Value Object는 `record`이고 compact constructor에서 검증한다. `new`로 만들어도 되고 필요하면 `of`를 둔다.
- 식별자는 `{Domain}Id` record이고 `generate()`를 제공한다. 다른 Context를 가리키는 `*ReferenceId`는 `generate()`가 없다.
- 상수는 `private static final int MAX_LENGTH = 1_000;`처럼 둘 수 있다.
- 실패는 Context의 Domain 예외를 `throw new ...Exception()`으로 던진다. JDK 기본 예외(`IllegalArgumentException` 등)는 쓰지 않는다.
- 상태 변경은 새 Aggregate를 반환한다. 다른 Aggregate는 식별자 VO로만 참조한다.

## Application

- 각 Application Service는 `final`이고 정확히 하나의 Inbound UseCase를 구현한다. 메서드 하나인 UseCase와 Port에는 `@FunctionalInterface`를 붙인다.
- 트랜잭션은 public Application Service 메서드에 둔다. 조회와 로그인은 `@Transactional(readOnly = true)`를 사용한다.
- Repository Port는 `save`, `create`, `update`, `find...`, `exists...`, `delete...`를 쓴다. `upsert`는 쓰지 않는다.
- Command는 record compact constructor에서 입력을 검증하고 실패 시 Context의 `InvalidCommandException`을 던진다.
- 저장소 기술 예외는 `RepositoryOperationException`으로 바꿔 Port 밖으로 전달한다.

## Adapter와 오류

- Controller는 요청 파싱, Command 생성, UseCase 호출, Response 변환만 담당한다. JPA 엔티티와 Spring Web 어노테이션은 와일드카드 import(`jakarta.persistence.*`, `org.springframework.web.bind.annotation.*`)를 쓸 수 있고, 그 외에는 쓰지 않는다.
- JPA Entity는 `adapter.out.persistence`에만 두고 `BaseEntity`를 상속한다. 기술 키 `Long id`와 도메인 식별 컬럼 `{target}Id` 또는 `publicId`(String, 길이 32, 유일)를 둔다.
- Mapper는 `final` 유틸리티 클래스다. 자식 행 키 UUID는 만들 수 있지만 시간 값은 만들지 않는다.
- 예외 응답은 Context별 `{Context}ExceptionHandler`(`@RestControllerAdvice`, `HIGHEST_PRECEDENCE`)가 sealed 예외를 `switch`로 나눠 `ProblemDetails.create(status, code, message)`로 만든다. 나머지는 `GlobalApiControllerAdvice`가 500으로 처리한다.
- Domain/Application/Outbound Port는 DB, HTTP, SDK, Spring 기술 예외를 노출하지 않는다.

## 품질

- 테스트는 한국어 `@DisplayName`과 `// given`, `// when`, `// then` 구조를 사용한다.
- 예외 테스트는 예외 타입과 메시지를 검증한다.
- 줄 길이는 130자 이하, 사용하지 않는 import는 남기지 않는다.
- 검증을 비활성화하거나 제외해 실패를 숨기지 않는다.
- Production, 테스트, build 설정, migration을 바꾸면 완료 전에 `./gradlew check`를 실행한다.
