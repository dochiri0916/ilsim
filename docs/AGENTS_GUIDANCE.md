# Codex Java/Spring Guidance

Java 21+, Spring Boot, JPA를 사용하고 `io.github.dochiri0916.build-convention`을 적용한 소스 모듈에 이 지침을 적용한다. 최신 사용자 요청과 대화에서 명시적으로 합의한 결정, 저장소의 `AGENTS.md`, 저장소의 기존 구조가 이 지침보다 우선한다. 기존 프로젝트의 작은 변경에서는 요청 범위 밖 구조 개편이나 기존 코드 일괄 마이그레이션을 하지 않는다.

상세 기준 저장소는 `~/programming/utility/build-convention`이다. 다음 경로는 현재 작업 저장소가 아니라 이 기준 저장소의 루트에서 해석한다.

- `docs/ARCHITECTURE.md`: 패키지, 계층, Domain/Application/Adapter 책임
- `docs/ERROR_HANDLING.md`: `global.exception`, ErrorCode, BusinessException, ProblemDetail
- `docs/TESTING.md`: 테스트 작성 방식과 검증 명령

## 핵심 구조

```
Domain <- Application <- Adapter
```

- Domain은 Spring, JPA, Lombok, Application, Adapter를 모른다. `global.exception`에서는 기술 독립적인 ErrorCode와 공통 예외 계약만 사용한다.
- Application은 같은 Context의 Domain과 자신이 소유한 Port를 사용한다. `global.exception`에서는 기술 독립적인 ErrorCode와 공통 예외 계약만 사용할 수 있다.
- Application은 실용적 예외로 Spring의 stereotype·transaction annotation과 compile-time Lombok을 사용할 수 있다. 따라서 이 구조는 strict Clean Architecture가 아니라 실용적 hexagonal/clean architecture다.
- Controller는 Inbound `UseCase`만 호출하고, Application Service는 Outbound `Port` 또는 무상태 Domain Service만 주입받는다.
- `{context}`는 Bounded Context이며 Aggregate와 같은 뜻으로 사용하지 않는다. 다른 Context의 모델·Application 타입·식별자 VO를 직접 import하지 않는다. 통합 Port 경계에서 현재 Context 소유 VO로 번역한다. 공유 식별자 계약이 필요하면 소유권과 버전이 명시된 Published Language module에서만 공유한다.
- `common` 패키지를 만들지 않는다.

## 패키지

```
com.example.project/
├── global/
│   ├── exception/
│   └── web/
└── {context}/
    ├── domain/{model,event,exception}/
    ├── application/{port/in,port/out,exception,service}/
    └── adapter/{in/web,out/persistence}/
```

`global.exception`에는 `ErrorCode`, `ErrorKind`, `BusinessException`, `DomainException`, `ApplicationException`, `GlobalExceptionHandler`를 둔다. 공통 계약 타입은 Spring과 HTTP 타입을 모르며, `GlobalExceptionHandler`만 Spring Web 타입을 사용한다. `global.web`에는 인증과 MVC 설정을 둔다.

## Domain

- Entity와 Aggregate Root는 불변 `final class`다. Value Object와 First-class Collection은 `record`다.
- 식별자는 `{Domain}Id` Value Object로 표현하며 DB 기술 키 `Long id`를 Domain에 두지 않는다.
- 신규 Aggregate는 `create`, 영속 상태 복원은 `restore`를 사용한다.
- 상태 변경은 새 Aggregate를 반환한다. ID 기반 `equals`와 `hashCode`를 구현한다.
- raw scalar는 VO 내부 또는 create/행위 경계에서만 받고, Aggregate 상태에는 VO/enum/불변 collection을 둔다.
- 다른 Aggregate는 객체가 아닌 식별자 VO로만 참조한다. JPA 연관관계와 Lazy Loading으로 Aggregate를 탐색하지 않는다.
- Business invariant 실패는 Domain 예외와 ErrorCode로 표현하며 JDK 기본 예외로 표현하지 않는다. 이미 검증된 구성요소를 받는 private Aggregate constructor의 구조적 null guard에 한해 `Objects.requireNonNull`을 허용한다.

## Application

- 각 Application Service는 정확히 하나의 Inbound UseCase를 구현한다.
- 트랜잭션은 public Application Service 메서드에 둔다. 조회는 `@Transactional(readOnly = true)`를 사용한다.
- 하나의 트랜잭션에서 여러 Aggregate를 직접 변경하거나 외부 API·메시지·메일·파일 I/O를 수행하지 않는다.
- Repository Port의 생성과 변경은 `create`와 `update`로 분리한다. `save`와 `upsert`는 사용하지 않는다.
- UseCase Command/Query/Result는 `application.port.in`에 두고, HTTP API Request/Response DTO는 `adapter.in.web`에 둔다.

## Adapter와 오류

- Controller는 요청 파싱, Command/Query 생성, UseCase 호출, Response 변환만 담당한다.
- JPA Entity는 `adapter.out.persistence`에만 두며 Domain과 분리한다. JPA Entity 간 객체 연관관계를 사용하지 않는다.
- `global.exception.GlobalExceptionHandler`는 Spring의 `ResponseEntityExceptionHandler`를 상속하고 `BusinessException`의 ErrorCode와 extensions를 RFC 9457 `ProblemDetail`로 변환한다.
- `@ExceptionHandler`는 단순 오류 응답에서 `ProblemDetail`을 직접 반환한다. status는 ErrorKind에서 변환하고, 안정적인 code와 안전한 extensions는 properties로 추가한다.
- 예외의 `getMessage()`를 그대로 detail로 노출하지 않는다. 기본값은 `ErrorCode.detail()`을 사용하고, 다국어가 실제로 필요할 때만 Web 계층에서 Spring `MessageSource`를 사용한다.
- 기본 구조에서는 별도 Exception Mapper Registry나 메시지 Provider를 만들지 않는다. 다중 프로토콜·API 버전처럼 실제 매핑 요구가 생길 때만 추가한다.
- `ProblemDetail`, `HttpStatus`, `@RestControllerAdvice`는 `global.exception` 또는 `adapter.in.web`에만 둔다.
- Domain/Application/Outbound Port는 DB, HTTP, SDK, Spring 기술 예외를 노출하지 않는다.

## 품질

- 테스트는 한국어 `@DisplayName`과 `// given`, `// when`, `// then` 구조를 사용한다.
- 예외 테스트는 예외 타입, ErrorCode, 필요한 실패 맥락을 검증한다.
- 검증을 비활성화하거나 제외해 실패를 숨기지 않는다.
- Production, 테스트, build 설정, migration을 바꾸면 완료 전에 `./gradlew check`를 실행한다.