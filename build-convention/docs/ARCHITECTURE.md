# Architecture Convention

## 목적

이 문서는 `io.github.dochiri0916.build-convention`을 사용하는 Java 21+/Spring Boot/JPA 프로젝트의 구조 기준이다. 기준 예시는 `tool-test-lab` 저장소이며, 이 문서에 없는 구조와 스타일은 검증에서 거부된다. Application의 Spring stereotype·transaction annotation과 compile-time Lombok은 실용적 예외로 허용한다.

## 의존성 방향

```text
Domain <- Application <- Adapter
```

- Domain은 Spring, JPA, Lombok, Application, Adapter를 모른다. 같은 Context의 Domain과 공유 커널(`global.domain`)만 사용한다.
- Application은 같은 Context의 Domain, 자신이 소유한 Port, 공유 커널(`global.domain`, `global.application.exception`)만 사용한다.
- Adapter는 Application Port와 Domain을 사용해 외부 기술을 연결한다.
- `{context}`는 Bounded Context다. 다른 Context의 모델·Application 타입은 직접 import하지 않고 통합 Port와 Adapter(예: `cart.adapter.out.product.ProductCatalogAdapter`)로 번역한다. Published Language가 필요하면 `publishedLanguagePackagePrefixes`로 명시한다.
- 묶음 Context(`catalog/category`, `catalog/product`)는 하나의 Context로 본다. 같은 묶음의 모듈끼리는 Port와 Domain 모델을 직접 사용할 수 있다. 묶음은 한 단계만 허용한다.

## 패키지

```text
com.example.project/
├── global/
│   ├── configuration/          # @Configuration, properties/
│   ├── domain/                 # 공유 값 객체(Money, Quantity), exception/
│   ├── application/exception/  # 공통 Application 예외
│   ├── exception/              # GlobalApiControllerAdvice, ProblemDetails
│   └── persistence/            # BaseEntity, RepositoryExceptionTranslator
└── {context}/ 또는 {context}/{module}/
    ├── domain/                 # Aggregate Root, model/, vo/, event/, exception/
    ├── application/
    │   ├── port/in/            # UseCase, Command, Result, command/, result/
    │   ├── port/out/
    │   ├── exception/
    │   └── service/
    └── adapter/
        ├── in/web/             # Controller, {Context}ExceptionHandler, request/, response/, exception/
        ├── in/bootstrap/
        └── out/persistence/    # 그 외 out/{target}/ 통합 Adapter
```

- `global`은 어떤 `{context}` 패키지도 import하지 않는다. `global.web`, `common` 패키지는 쓰지 않는다.
- `port.in.usecase`, `port.in.query`, `adapter.in.scheduler/messaging/event`는 쓰지 않는다.
- `port.in` 바로 아래 타입은 `UseCase`, `Command`, `Result`로 끝난다. `result/`에는 `*Summary`도 둘 수 있다.
- 와일드카드 import는 `jakarta.persistence.*`, `org.springframework.web.bind.annotation.*`만 허용한다.

## Domain

- Aggregate Root는 `domain` 바로 아래 또는 `domain.model`에 둔다. Value Object와 First-class Collection은 `domain.vo`의 `record` 또는 `enum`이다.
- Entity와 Aggregate Root는 불변 `final class`다. 상태는 Value Object·enum·First-class Collection 타입의 `private final` 필드에 두고, 값을 받는 constructor는 `private`이다.
- 신규 상태는 `create`, 영속 상태 복원은 `restore`로 만든다. 상태 변경은 새 Aggregate를 반환한다.
- 도메인 이벤트는 `domain.event`의 `record`다. 과거형 이름에 `Event` 접미사가 없고, compact constructor에서 검증한다.
- Value Object는 compact constructor에서 검증한다. `new`로 만들어도 되고 필요하면 `of`를 둔다.
- 식별자는 `{Domain}Id` record이고 `generate()`를 제공한다. 다른 Context를 가리키는 `*ReferenceId`는 `generate()`가 없어도 된다.
- `private static final` 상수를 둘 수 있다. 길이 검증은 `isBlank()` 또는 `.length() < 상수` 비교로 한다.
- 실패는 Context의 Domain 예외로 표현한다. JDK 기본 예외는 쓰지 않는다.
- 다른 Aggregate는 `{Target}Id` VO로만 참조한다. JPA 연관관계로 Aggregate를 탐색하지 않는다.

## Application

- Controller는 Inbound `*UseCase`만 호출한다.
- Application Service는 `final`이고 하나의 UseCase를 구현하며 Outbound `*Port` 또는 무상태 Domain Service만 주입받는다. 메서드 하나인 UseCase와 Port에는 `@FunctionalInterface`를 붙인다.
- 트랜잭션은 public Application Service 메서드에 둔다. 조회와 `login`/`authenticate`에는 `readOnly = true`를 사용한다.
- 트랜잭션 중 외부 API, 메시지, 메일, 파일 I/O를 수행하지 않는다.
- Repository Port는 `save`, `create`, `update`, `find...`, `exists...`, `delete...`를 쓴다. `upsert`는 쓰지 않는다.

## Adapter

- Web Adapter는 요청 파싱, Command 생성, UseCase 호출, Response 변환만 담당한다. Request/Response DTO는 Web Adapter에 둔다.
- Persistence Adapter는 Outbound Port를 구현한다. JPA Entity는 `BaseEntity`를 상속하고 기술 키 `Long id`와 식별 컬럼 `{target}Id` 또는 `publicId`(유일)를 둔다. 유일성은 컬럼 또는 `@Table(uniqueConstraints)`로 선언한다.
- Mapper는 `final` 유틸리티 클래스로 구조 변환만 한다. 자식 행 키 UUID는 만들 수 있지만 현재 시간은 만들지 않는다.
- Spring component는 생성자 주입과 `@RequiredArgsConstructor`를 사용한다.

세부 오류 처리는 [ERROR_HANDLING.md](ERROR_HANDLING.md), 테스트와 검증은 [TESTING.md](TESTING.md)를 따른다.
