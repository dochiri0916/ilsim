# Error Handling Convention

## 목표

오류 처리는 Spring Framework의 RFC 9457 `ProblemDetail` 지원을 그대로 사용하되, Domain/Application이 HTTP와 Spring 타입을 알지 않도록 분리한다. 기본 구조에서는 별도의 Exception Mapper Registry를 만들지 않는다.

```text
Domain/Application ErrorCode + BusinessException
                         ↓
GlobalExceptionHandler extends ResponseEntityExceptionHandler
                         ↓
ProblemDetail
```

## 기준 구조

```text
global.exception/
├── ErrorCode
├── ErrorKind
├── BusinessException
├── DomainException
├── ApplicationException
└── GlobalExceptionHandler
```

`ErrorCode`, `ErrorKind`, `BusinessException`, `DomainException`, `ApplicationException`은 계층에서 공유하는 순수 Java 계약이다. 이 타입들은 Spring, `HttpStatus`, `ProblemDetail`, JPA, SDK 타입에 의존하지 않는다.

`GlobalExceptionHandler`만 Spring Web 타입을 사용한다.

## ErrorCode와 ErrorKind

`ErrorCode`는 다음 값만 제공한다.

```java
public interface ErrorCode {
    String code();
    ErrorKind kind();
    String detail();
}
```

- Context별 Domain/Application ErrorCode는 `domain.exception` 또는 `application.exception`의 enum으로 정의한다.
- ErrorCode enum은 공통 `ErrorCode` 계약을 구현할 수 있다.
- `code`는 클라이언트와 로그에서 사용하는 안정적인 식별자다.
- `kind`는 HTTP status 자체가 아니라 공통 오류 분류다.
- `detail`은 기본 사용자 노출 문구다. 다국어가 실제 요구사항이 되면 Web 계층에서 Spring `MessageSource`로 대체할 수 있다.
- ErrorCode와 ErrorKind에 `HttpStatus`, `ProblemDetail`, Spring annotation을 넣지 않는다.

`ErrorKind`와 HTTP status의 기본 매핑은 다음과 같다.

| ErrorKind | HTTP status |
|---|---|
| `INVALID_INPUT` | 400 Bad Request |
| `NOT_FOUND` | 404 Not Found |
| `CONFLICT` | 409 Conflict |
| `FORBIDDEN` | 403 Forbidden |
| `INVALID_STATE` | 409 Conflict |

이 매핑은 `GlobalExceptionHandler` 한 곳에서 수행한다.

## BusinessException

`BusinessException`은 다음 책임만 가진다.

- ErrorCode 보관 및 `errorCode()` 노출
- ErrorCode detail을 RuntimeException message로 보관
- 실패 진단용 extensions 보관
- extensions의 defensive copy와 수정 불가능한 형태 보장

extensions에는 API 응답에 공개해도 안전한 값만 넣는다. 비밀번호, 토큰, 원문 개인정보, 내부 SQL이나 외부 시스템 응답 본문을 넣지 않는다.

Domain/Application의 concrete 예외는 다음 규칙을 따른다.

- Domain 예외는 `domain.exception`, Application 예외는 `application.exception`에 둔다.
- 각각 `DomainException`, `ApplicationException` 기반 타입을 사용할 수 있다.
- concrete 예외는 private constructor와 의미 있는 static factory를 사용한다.
- 예외 타입, ErrorCode, 필요한 Value Object와 실패 상태로 오류를 표현한다.
- DB, HTTP, SDK, Spring 기술 예외를 생성자·필드·반환 타입으로 노출하지 않는다.

### Context별 예외 통합

같은 Context 안에서 호출자가 concrete 예외 타입으로 복구·분기할 요구가 없다면, 오류별 예외 클래스를 만들지 않고 Context별 예외 하나로 통합한다. 예를 들어 `ProductDomainException` 또는 `ProductApplicationException`이 해당 Context의 모든 Domain/Application 오류를 표현할 수 있다.

- 통합 예외는 `private` constructor와 의미가 분명한 static factory만 공개한다.
- factory 이름은 `required()`처럼 일반적인 이름 대신 `productNameRequired()`, `invalidProductId(productId)`, `insufficientStock(currentStock, requestedQuantity)`처럼 실패 대상과 조건을 드러낸다.
- 각 factory는 대응하는 ErrorCode와 API에 공개해도 안전한 실패 맥락 extensions를 설정한다.
- 타입 자체로 복구·분기해야 하는 실제 요구가 생길 때만 별도 concrete 예외 타입을 추가한다.

`com.dochiri.lint-convention`은 기본적으로 Context·계층별 concrete 예외를 하나만 허용한다. 예외 타입 분기가 필요한 Context는 Gradle 설정에서 `"{basePackage}.{context}:domain"` 또는 `"{basePackage}.{context}:application"`을 allowlist에 명시한다.

```groovy
hexagonalConvention {
    exceptionTypeSplitAllowlist = [
            'com.example.payment:domain'
    ] as Set
}
```

## GlobalExceptionHandler

`global.exception.GlobalExceptionHandler`는 `ResponseEntityExceptionHandler`를 상속하고 `@RestControllerAdvice`를 선언한다.

```java
@RestControllerAdvice
public final class GlobalExceptionHandler
        extends ResponseEntityExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ProblemDetail handleBusinessException(
            final BusinessException exception
    ) {
        final ErrorCode errorCode = exception.errorCode();
        final HttpStatus status = statusOf(errorCode.kind());
        final ProblemDetail problemDetail =
                ProblemDetail.forStatusAndDetail(status, errorCode.detail());

        problemDetail.setProperty("code", errorCode.code());
        exception.extensions().forEach(problemDetail::setProperty);
        return problemDetail;
    }
}
```

다음 원칙을 지킨다.

- `@ExceptionHandler`는 `ProblemDetail`을 직접 반환한다. 단순한 오류 응답을 `ResponseEntity<ProblemDetail>`로 다시 감싸지 않는다.
- `ProblemDetail.status`를 실제 HTTP status의 기준으로 사용한다.
- `instance`를 임의로 만들지 않는다. 설정하지 않으면 Spring이 현재 요청 경로를 사용한다.
- 안정적인 오류 식별자는 `code` property로 추가한다.
- 안전한 extensions는 `ProblemDetail.setProperty`로 추가한다.
- Handler는 `ErrorCode.kind()`, `code()`, `detail()`을 사용해 status, code, detail과 properties를 구성한다.
- 예외의 `getMessage()`를 그대로 `ProblemDetail.detail`로 노출하지 않는다. `ErrorCode.detail()` 또는 Web 계층의 `MessageSource` 결과를 사용한다.
- Context의 concrete Domain/Application 예외를 직접 import하지 않고 공통 `BusinessException`을 처리한다.
- 예상하지 못한 예외는 내부에 stack trace를 기록하고 일반적인 500 응답으로 변환한다.
- Spring MVC가 발생시키는 기본 예외는 `ResponseEntityExceptionHandler`의 기본 처리와 override hook을 사용한다.

Spring 공식 참고 문서:

- <https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html>
- <https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/servlet/mvc/method/annotation/ResponseEntityExceptionHandler.html>

## 확장 기준

기본 구조에서는 `ApiExceptionMapper`, Mapper Registry, 별도 오류 메시지 Provider를 만들지 않는다.

다음 요구가 실제로 생겼을 때만 Adapter별 매핑 계층을 추가한다.

- REST와 gRPC 또는 메시징에서 동일 예외를 서로 다르게 표현해야 한다.
- API 버전별 오류 계약이 다르다.
- 외부 모듈이 런타임에 오류 Mapper를 등록해야 한다.
- 하나의 공통 BusinessException 계약으로 통합할 수 없는 레거시 예외가 많다.

다국어가 필요하면 Spring이 공식 지원하는 `MessageSource`를 Web 계층에서 사용한다. 다국어 요구가 없으면 `ErrorCode.detail()`을 기본값으로 유지한다.

## 배치 제한

- `ProblemDetail`, `HttpStatus`, `ErrorResponse`, `ErrorResponseException`, `ResponseEntityExceptionHandler`, `@RestControllerAdvice`는 `global.exception` 또는 `adapter.in.web`에만 둔다.
- Controller는 직접 `ProblemDetail`을 만들지 않는다.
- Domain/Application/Outbound Port는 Spring Web 오류 타입을 사용하지 않는다.
