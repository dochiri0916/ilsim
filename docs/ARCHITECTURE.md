# Architecture Convention

## 목적

이 문서는 `com.dochiri.lint-convention`을 사용하는 Java 21+/Spring Boot/JPA 프로젝트의 구조 기준이다. 이 프로젝트는 strict Clean Architecture가 아니라 Application의 Spring stereotype·transaction annotation과 compile-time Lombok을 명시적으로 허용하는 실용적 hexagonal/clean architecture를 따른다.

## 의존성 방향

```text
Domain <- Application <- Adapter
```

- Domain은 Spring, JPA, Lombok, Application, Adapter를 모른다. `global.exception`에서는 기술 독립적인 `ErrorCode`, `ErrorKind`, `DomainException`, `BusinessException` 계약만 사용할 수 있다.
- Application은 같은 Context의 Domain과 자신이 소유한 Port를 사용한다. `global.exception`에서는 기술 독립적인 `ErrorCode`, `ErrorKind`, `ApplicationException`, `BusinessException` 계약만 사용할 수 있다.
- Adapter는 Application Port와 Domain을 사용해 외부 기술을 연결한다.
- `{context}`는 Bounded Context이며 Aggregate와 같은 뜻으로 사용하지 않는다. 다른 Context의 모델·Application 타입·식별자 VO를 직접 import하지 않는다. 통합 Port 경계에서 현재 Context가 소유한 VO로 번역한다. 공유 식별자 계약이 필요하면 소유권과 버전이 명시된 Published Language module만 `publishedLanguagePackagePrefixes`로 명시해 허용한다.

## 패키지

```text
com.example.project/
├── global/
│   ├── exception/
│   └── web/
└── {context}/
    ├── domain/
    │   ├── model/
    │   ├── event/
    │   └── exception/
    ├── application/
    │   ├── port/in/
    │   ├── port/out/
    │   ├── exception/
    │   └── service/
    └── adapter/
        ├── in/web/
        └── out/persistence/
```

`global.exception`에는 프로젝트 공통 예외 계약과 `GlobalExceptionHandler`를 둔다. 공통 예외 계약은 Spring Web 타입을 사용하지 않고, `GlobalExceptionHandler`만 `ProblemDetail`과 `HttpStatus`를 사용한다. `global.web`에는 인증·OpenAPI·MVC 설정을 둔다. `common` 패키지는 사용하지 않는다.

## Domain

- Entity와 Aggregate Root는 불변 `final class`다. 상태는 `private final` 필드에 두고 값을 받는 constructor는 `private`으로 둔다.
- Value Object와 First-class Collection은 `record`다.
- `Product` 같은 Aggregate는 `create`로 신규 상태를 만들고 `restore`로 영속 상태를 복원한다. `reconstitute`는 기존 코드 호환 외에는 사용하지 않는다. `restore`는 새 ID, 시간, 이벤트를 만들지 않는다.
- 상태 변경은 새 Aggregate를 반환한다. 실제 변화가 없는 명시적 no-op만 `this`를 반환할 수 있다.
- Aggregate의 동등성은 식별자 VO만으로 구현한다.
- `String`, primitive, wrapper는 VO 내부 또는 create/행위 경계에서만 받고, Aggregate 상태로 저장하지 않는다. Business invariant 실패는 Domain Exception과 ErrorCode로 표현한다. 이미 검증된 구성요소를 받는 private Aggregate constructor의 구조적 null guard에 한해 `Objects.requireNonNull`을 허용한다.
- Aggregate의 단순 ID/VO/enum collection은 `List.copyOf`, `Set.copyOf`, `Map.copyOf`로 방어 복사하면 직접 보관할 수 있다. 컬렉션 자체에 행위·계산·독립 불변식이 있으면 First-class Collection으로 모델링한다.
- 다른 Aggregate는 객체가 아닌 `{Target}Id` VO로만 참조한다. JPA 연관관계와 Lazy Loading으로 Aggregate를 탐색하지 않는다.

## Application

- Controller는 Inbound `*UseCase`만 호출한다.
- Application Service는 하나의 UseCase를 구현하고 Outbound `*Port` 또는 무상태 Domain Service만 주입받는다.
- 트랜잭션은 public Application Service 메서드에 둔다. 조회에는 `readOnly = true`를 사용한다.
- 트랜잭션 중 외부 API, 메시지, 메일, 파일 I/O를 수행하지 않는다.
- Command/Query의 raw 값은 대응 VO로 정규화한 뒤 Port에 전달한다.
- Repository Port의 생성과 변경은 `create(Aggregate)`와 `update(Aggregate)`로 분리한다. `save`나 `upsert`처럼 생성 여부를 Persistence Adapter에 맡기는 변경 계약은 사용하지 않는다.

## Adapter

- Web Adapter는 요청 파싱, Command/Query 생성, UseCase 호출, Response 변환만 담당한다. HTTP Request/Response DTO는 Web Adapter에 두고 UseCase Command/Query/Result와 분리한다.
- Persistence Adapter는 Outbound Port를 구현한다. JPA Entity는 Domain과 분리하며 Entity 간 객체 연관관계를 사용하지 않는다.
- Mapper는 Entity와 Domain의 구조 변환만 담당한다.
- Spring component는 생성자 주입과 `@RequiredArgsConstructor`를 사용한다.

세부 오류 처리는 [ERROR_HANDLING.md](ERROR_HANDLING.md), 테스트와 검증은 [TESTING.md](TESTING.md)를 따른다.
