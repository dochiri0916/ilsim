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
│   ├── domain/                 # vo/(Money, Quantity), exception/, outbox/
│   ├── application/exception/  # 공통 Application 예외
│   ├── exception/              # GlobalApiControllerAdvice, ProblemDetails
│   └── persistence/            # BaseEntity, RepositoryExceptionTranslator
└── {context}/ 또는 {context}/{module}/
    ├── domain/                 # Aggregate Root, model/, vo/, event/, exception/, outbox/
    ├── application/
    │   ├── port/in/            # usecase/, command/, query/, result/
    │   ├── port/out/
    │   ├── exception/
    │   └── service/
    └── adapter/
        ├── in/web/             # Controller, {Context}ExceptionHandler, request/, response/, exception/
        ├── in/bootstrap/
        └── out/persistence/    # 그 외 out/{target}/ 통합 Adapter
```

- `global`은 어떤 `{context}` 패키지도 import하지 않는다. `global.web`, `common` 패키지는 쓰지 않는다.
- 입력 계약은 `port.in.usecase`·`command`·`query`·`result`로 분리한다. 메시지 수신은 `adapter.in.messaging`에 둔다.
- `port.in` 바로 아래에는 타입을 두지 않는다. 조회 조건이 있으면 Query로 전달하고 조건 없는 조회에는 빈 Query를 만들지 않는다.
- 와일드카드 import는 `jakarta.persistence.*`, `org.springframework.web.bind.annotation.*`만 허용한다.

## Domain

- Aggregate Root는 `domain` 바로 아래 또는 `domain.model`에 둔다. Value Object와 First-class Collection은 `domain.vo`의 `final class`다. 상태 enum도 `domain.vo`에 둔다.
- Entity와 Aggregate Root는 불변 `final class`다. 상태는 Value Object·enum·First-class Collection 타입의 `private final` 필드에 두고, 값을 받는 constructor는 `private`이다.
- 신규 상태는 `create`, 영속 상태 복원은 `restore`로 만든다. 상태 변경은 식별자를 유지하는 새 Aggregate를 반환한다.
- 도메인 이벤트는 `domain.event`의 `record`다. 과거형 이름에 `Event` 접미사가 없고, compact constructor에서 검증한다.
- Value Object는 private constructor에서 불변식을 검증하고 `of()`로 생성한다. `parse()`·`empty()`도 허용한다.
- 식별자는 UUID v7을 감싸는 `{Domain}Id` VO이며 생성자에서 version 7을 검증하고 `generate()`로 발급한다. 다른 Context를 가리키는 `*ReferenceId`는 `generate()`가 없어도 된다.
- `private static final` 상수를 둘 수 있다. 길이 검증은 `isBlank()` 또는 `.length() < 상수` 비교로 한다.
- 실패는 Context의 Domain 예외로 표현한다. JDK 기본 예외는 쓰지 않는다.
- 다른 Aggregate는 `{Target}Id` VO로만 참조한다. JPA 연관관계로 Aggregate를 탐색하지 않는다.

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

- Controller는 Inbound `*UseCase`만 호출한다.
- Application Service는 `final`이고 하나의 UseCase를 구현하며 Outbound `*Port` 또는 무상태 Domain Service만 주입받는다. 메서드 하나인 UseCase와 Port에는 `@FunctionalInterface`를 붙인다.
- 트랜잭션은 public Application Service 메서드에 둔다. 조회와 `login`/`authenticate`에는 `readOnly = true`를 사용한다.
- 트랜잭션 중 외부 API, 메시지, 메일, 파일 I/O를 수행하지 않는다.
- Repository Port는 `save`, `create`, `update`, `find...`, `exists...`, `delete...`를 쓴다. `upsert`는 쓰지 않는다.

## Adapter

- Web Adapter는 요청 파싱, Command 생성, UseCase 호출, Response 변환만 담당한다. Request/Response DTO는 Web Adapter에 둔다.
- Persistence Adapter는 Outbound Port를 구현한다. JPA Entity는 `BaseEntity`를 상속하고 자동 생성 기술 키 `Long id`와 자체 식별 컬럼 `publicId`/`public_id`(36자, 필수, 유일)를 둔다. 부모·외부 참조의 `{target}Id` 이름과 Inbox·Outbox·스냅샷의 목적별 키를 유지한다. 이름 있는 유니크 제약은 `@Table(uniqueConstraints)`에 직접 선언하고 동일 컬럼에 중복 선언하지 않는다. JPA 관계·`@ElementCollection`은 쓰지 않으며 상태는 String으로 저장한다.
- Mapper는 `final` 유틸리티 클래스로 구조 변환만 한다. 자식 행 키 UUID는 만들 수 있지만 현재 시간은 만들지 않는다.
- Spring component는 명시적 생성자 또는 `@RequiredArgsConstructor`로 private final 의존성을 주입한다.

세부 오류 처리는 [ERROR_HANDLING.md](ERROR_HANDLING.md), 테스트와 검증은 [TESTING.md](TESTING.md)를 따른다.
