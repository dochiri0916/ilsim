# Error Handling Convention

## 목표

오류는 Context별 sealed 예외로 표현하고, Web 계층의 `{Context}ExceptionHandler`가 Spring `ProblemDetail`로 바꾼다. Domain/Application은 HTTP와 Spring 타입을 모른다. 기준 예시는 `tool-test-lab` 저장소다.

```text
{Context}DomainException / {Context}ApplicationException (sealed)
                         ↓
{Context}ExceptionHandler (@RestControllerAdvice, HIGHEST_PRECEDENCE)
                         ↓
ProblemDetails.create(status, code, message)
```

공통 `ErrorCode`, `ErrorKind`, `BusinessException` 계약은 쓰지 않는다.

## 예외 계층

```text
{context}/domain/exception/
├── {Context}DomainException        # abstract sealed, extends RuntimeException
└── *Exception                      # final, extends {Context}DomainException
{context}/application/exception/
├── {Context}ApplicationException   # abstract sealed, extends RuntimeException
├── InvalidCommandException
└── *Exception                      # final
global/application/exception/       # 공유 Application 예외(RepositoryOperationException)
global/domain/exception/            # 공유 값 객체 예외
```

```java
public abstract sealed class CartDomainException extends RuntimeException
        permits CartIdRequiredException, InvalidCartIdException {
    protected CartDomainException(final String message) {
        super(message);
    }
}

public final class CartIdRequiredException extends CartDomainException {
    public CartIdRequiredException() {
        super("장바구니 ID는 필수입니다.");
    }
}
```

- Context 기본 예외는 `abstract sealed`이고 constructor는 `protected`다.
- 구체 예외는 `final`이고 같은 Context·계층의 sealed 기반 예외를 상속한다. 기반 예외의 `permits`에는 실제 존재하는 모든 final 직계 예외를 명시한다. 공통 VO 예외는 `GlobalDomainException`을 상속한다.
- 구체 예외는 public constructor와 문자열 메시지를 사용할 수 있다. `throw new ...Exception()`으로 던진다.
- 실패 맥락 필드가 필요하면 같은 이름의 accessor와 함께 둔다.
- JDK 기본 예외(`IllegalArgumentException` 등)로 비즈니스 실패를 표현하지 않는다.

## Web 변환

```java
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class CartExceptionHandler {
    private final ProblemDetails problemDetails;

    @ExceptionHandler(CartDomainException.class)
    public ProblemDetail handle(final CartDomainException exception) {
        return switch (exception) {
            case CartIdRequiredException e -> respond(e, HttpStatus.BAD_REQUEST, "CART_ID_REQUIRED");
            case InvalidCartIdException e -> respond(e, HttpStatus.BAD_REQUEST, "CART_ID_INVALID");
        };
    }
}
```

- `{Context}ExceptionHandler`(또는 `*ControllerAdvice`)는 `adapter.in.web` 루트에 둔다.
- sealed 예외를 `switch`로 나눠 status와 code를 정한다. 새 예외를 추가하면 컴파일러가 누락을 알려준다.
- 응답은 `global.exception.ProblemDetails.create`로 만들고 `code`·`timestamp`를 포함한다. Controller와 처리기에서 Spring `ProblemDetail` 팩터리를 직접 호출하지 않는다.
- 업무·공통 처리기는 `HIGHEST_PRECEDENCE`, 미처리 예외 전역 처리기는 `LOWEST_PRECEDENCE`를 사용한다. 업무 오류는 info 또는 warn, 기술 오류는 error 로그를 남긴다.
- 저장소·미처리 예외는 500·`INTERNAL_ERROR`·한국어 공통 메시지로 응답한다. URI·HTTP 메서드·원인 예외 스택은 로그에 기록하고 응답에는 내부 예외 메시지를 노출하지 않는다.
- `@Valid @RequestBody` 입력 검증 실패도 공통 응답 형식을 사용한다. 템플릿의 `handleMethodArgumentNotValid`는 거부된 필드 값 대신 입력 형식을 확인하라는 공통 400 메시지를 제공한다.

## 배치 제한

- `ProblemDetail`, `HttpStatus`, `@RestControllerAdvice`는 `global.exception` 또는 `adapter.in.web`에만 둔다.
- Controller는 직접 `ProblemDetail`을 만들지 않는다.
- Domain/Application/Outbound Port는 DB, HTTP, SDK, Spring 기술 예외를 노출하지 않는다. 저장소 기술 예외는 `RepositoryOperationException`으로 바꾼다.
